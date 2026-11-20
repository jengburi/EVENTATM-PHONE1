"""두 단계 작업 흐름.

1) analyze(): 영상 → 음성 인식 → 편집 계획(plan.json)   ※ 영상은 아직 건드리지 않음
2) apply():   (사용자가 확인/수정한) plan.json → MP4 + SRT + ASS + 컷 목록
"""
from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Callable

from . import analyze as an
from . import media, subtitles, timeline, transcribe
from .config import load_preset

Progress = Callable[[float, str], None]


def _noop(p: float, msg: str) -> None:
    pass


def work_dir_for(source: str | Path, out_root: str | Path | None = None) -> Path:
    src = Path(source)
    root = Path(out_root) if out_root else src.parent / "StoryCut_결과"
    d = root / src.stem
    d.mkdir(parents=True, exist_ok=True)
    return d


def analyze(source: str | Path, preset_id: str, model: str = "medium", transcript_path: str | Path | None = None,
            keywords: list[str] | None = None, out_root: str | Path | None = None,
            progress: Progress = _noop) -> Path:
    preset = load_preset(preset_id)
    wd = work_dir_for(source, out_root)
    progress(0.02, "영상 정보 읽는 중")
    info = media.probe(source)

    tpath = wd / "transcript.json"
    if transcript_path:
        tr = transcribe.load(transcript_path)
        progress(0.6, "기존 대본 사용")
    elif tpath.exists():
        tr = transcribe.load(tpath)
        progress(0.6, "이전에 인식한 대본 재사용 (다시 하려면 transcript.json 삭제)")
    else:
        if not info["has_audio"]:
            raise RuntimeError("이 영상에는 소리가 없습니다.")
        wav = media.extract_audio(source, wd / "audio16k.wav")
        tr = transcribe.transcribe(wav, model_size=model,
                                   progress=lambda p, m: progress(0.05 + 0.55 * p, m))
    transcribe.save(tr, tpath)

    progress(0.65, "무음·작업 소리 구간 찾는 중")
    silences = media.detect_silence(source) if info["has_audio"] else []
    progress(0.8, "문장·말버릇·다시 말한 부분 분석 중")
    plan = an.build_plan(tr, preset, str(Path(source).resolve()), info, keywords)
    plan["silences"] = silences
    clips = timeline.build_clips(plan, preset, silences)
    plan["stats"]["estimated_duration"] = round(timeline.total_duration(clips), 1)
    plan["warnings"] = _warnings(plan, preset, tr)
    ppath = wd / "plan.json"
    save_plan(plan, ppath)
    progress(1.0, "분석 완료 - 편집 계획을 확인해 주세요")
    return ppath


def _warnings(plan: dict, preset: dict, tr: dict) -> list[str]:
    w = []
    est = plan["stats"]["estimated_duration"]
    tgt = preset["target_duration"]
    if not tr["words"]:
        w.append("인식된 말이 없습니다. 말 없는 작업 영상의 자동 장면 분류는 3단계 기능입니다.")
    if est > tgt * 1.1:
        w.append(f"예상 길이 {est:.0f}초가 목표 {tgt}초보다 깁니다. 빼도 되는 문장의 체크를 해제하세요. "
                 "(2단계에서 AI가 스토리 기준으로 줄여 줍니다)")
    if est < preset.get("min_duration", 0):
        w.append(f"예상 길이 {est:.0f}초가 권장 최소 {preset['min_duration']}초보다 짧습니다.")
    if plan["stats"]["soft_fillers_flagged"]:
        w.append(f"'그/뭐/이제/막' 같은 말 {plan['stats']['soft_fillers_flagged']}개는 의미가 있을 수 있어 남겨 두었습니다.")
    return w


def save_plan(plan: dict, path: str | Path) -> None:
    Path(path).write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")


def load_plan(path: str | Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def apply(plan_path: str | Path, progress: Progress = _noop, burn_captions: bool | None = None) -> dict:
    plan_path = Path(plan_path)
    plan = load_plan(plan_path)
    preset = load_preset(plan["preset"])
    wd = plan_path.parent
    stem = f"{Path(plan['source']).stem}_{preset['id']}"

    plan["stats"] = an.summarize(plan, preset)
    clips = timeline.build_clips(plan, preset, plan.get("silences"))
    total = timeline.total_duration(clips)
    caps = subtitles.build_captions(plan, clips, preset)

    srt = subtitles.write_srt(caps, wd / f"{stem}.srt")
    ass = subtitles.write_ass(caps, preset, wd / f"{stem}.ass", plan.get("keywords"))
    cut_csv = write_cut_list(clips, plan, wd / f"{stem}_컷목록.csv")

    burn = preset["captions"]["burn_in"] if burn_captions is None else burn_captions
    from .render import render
    mp4 = render(plan["source"], clips, preset, wd / f"{stem}.mp4",
                 has_audio=plan["media"]["has_audio"], ass_path=ass if burn else None,
                 progress=progress, total=total)
    # 자막 없는 버전도 함께 (캡컷에서 SRT를 직접 얹어 수정하고 싶을 때)
    clean = None
    if burn:
        progress(0.0, "자막 없는 버전 만드는 중 (캡컷 편집용)")
        clean = render(plan["source"], clips, preset, wd / f"{stem}_자막없음.mp4",
                       has_audio=plan["media"]["has_audio"], ass_path=None, progress=progress, total=total)
    guide = write_capcut_guide(wd, stem, total, clean is not None)
    return {"mp4": mp4, "mp4_clean": clean, "srt": srt, "ass": ass, "cut_list": cut_csv,
            "guide": guide, "duration": total, "clips": len(clips), "captions": len(caps)}


def write_cut_list(clips: list[dict], plan: dict, path: Path) -> Path:
    sents = {s["id"]: s for s in plan["sentences"]}
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["번호", "구분", "원본 시작(초)", "원본 끝(초)", "결과 시작(초)", "결과 끝(초)", "문장"])
        for k, c in enumerate(clips, 1):
            text = " / ".join(sents[s]["text"] for s in c["sentences"] if s in sents) or "(작업 소리)"
            kind = "오프닝" if c["pass"] == "hook" else ("작업 소리" if c.get("kind") == "ambient" else "본편")
            w.writerow([k, kind, c["src_start"], c["src_end"],
                        c["out_start"], c["out_end"], text])
    return path


def write_capcut_guide(wd: Path, stem: str, total: float, has_clean: bool) -> Path:
    clean_name = f"{stem}_자막없음.mp4" if has_clean else f"{stem}.mp4"
    text = f"""JBWOOD AI StoryCut - 캡컷에서 이어서 편집하는 방법
==================================================

결과 길이: {total:.1f}초

[A] 자막까지 다 들어간 완성본
    {stem}.mp4  → 그대로 업로드하거나 캡컷에 넣어 효과만 추가

[B] 캡컷에서 자막을 직접 고치고 싶을 때 (추천)
    1. 캡컷 PC 실행 → 새 프로젝트
    2. '가져오기'로 {clean_name} 를 불러와 타임라인에 올립니다
    3. 상단 '텍스트(자막)' → '자막 추가/가져오기' → {stem}.srt 선택
       (캡컷 메뉴 이름은 버전마다 조금 다를 수 있어요)
    4. 자막 스타일에서 글꼴/테두리/색을 고르고 '모두 적용'

[C] 컷 위치 확인
    {stem}_컷목록.csv 를 엑셀로 열면 원본 몇 초를 썼는지 볼 수 있어요.

다시 편집하려면 프로그램에서 plan.json(편집 계획)을 열어 문장 체크를 바꾸고
'2. 편집 적용'만 다시 누르면 됩니다. 음성 인식은 다시 하지 않아요.
"""
    p = wd / "캡컷_가져오기_안내.txt"
    p.write_text(text, encoding="utf-8-sig")
    return p
