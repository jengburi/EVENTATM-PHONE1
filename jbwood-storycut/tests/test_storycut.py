"""자동 테스트: python -m pytest tests -q"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))

from storycut import analyze, keywords, media, pipeline, subtitles, timeline  # noqa: E402
from storycut.config import list_presets, load_preset  # noqa: E402


def W(text, s, e):
    return {"text": text, "start": s, "end": e, "prob": 0.9}


def test_presets_load():
    ids = list_presets()
    assert set(ids) == {"shorts_story", "vlog_talk", "making_process"}
    for i in ids:
        p = load_preset(i)
        assert p["captions"]["emphasis_color"] == "#FFD700"


def test_fillers_and_stutter_tagged():
    words = [W("음", 0, .2), W("이", .3, .4), W("나무", .45, .7), W("이", .8, .9), W("나무", .95, 1.2),
             W("좋아요.", 1.3, 1.7)]
    analyze.tag_words(words, load_preset("shorts_story")["cut"])
    assert words[0]["tag"] == "filler" and not words[0]["keep"]
    # "이 나무 이 나무": 단어 단위로는 '이'만 연속이 아니므로 문장 재시작 감지로 처리됨
    assert words[5]["keep"]


def test_restart_split_and_retake():
    words = [W("근데", 0, .3), W("처음에", .35, .7), W("재단을", .75, 1.1),
             W("근데", 1.8, 2.1), W("처음에", 2.15, 2.5), W("재단을", 2.55, 2.9), W("잘못했어요.", 2.95, 3.5)]
    tr = {"words": words}
    plan = analyze.build_plan(tr, load_preset("shorts_story"), "x.mp4", {"duration": 4, "has_audio": True})
    kept = [s["text"] for s in plan["sentences"] if s["keep"]]
    dropped = [s for s in plan["sentences"] if not s["keep"]]
    assert kept == ["근데 처음에 재단을 잘못했어요."]
    assert dropped and "다시 말함" in dropped[0]["reason"]


def test_meaning_preserved_no_text_changes():
    """편집은 지우기만 한다: 남은 단어 텍스트는 원문 그대로."""
    words = [W("오늘은", 0, .4), W("어", .5, .6), W("도마를", .7, 1.0), W("만들어요.", 1.1, 1.6)]
    plan = analyze.build_plan({"words": words}, load_preset("shorts_story"), "x.mp4",
                              {"duration": 2, "has_audio": True})
    kept = [w["text"] for w in plan["words"] if w["keep"]]
    assert kept == ["오늘은", "도마를", "만들어요."]


def test_clips_never_cut_inside_words():
    words = [W("하나", 0.5, 0.9), W("둘", 2.5, 2.9), W("셋.", 3.0, 3.4)]
    plan = analyze.build_plan({"words": words}, load_preset("shorts_story"), "x.mp4",
                              {"duration": 5, "has_audio": True})
    clips = timeline.build_clips(plan, load_preset("shorts_story"), [])
    for w in plan["words"]:
        inside = [c for c in clips if c["src_start"] <= w["start"] and w["end"] <= c["src_end"]]
        assert inside, f"단어가 잘림: {w}"
    # 긴 침묵(0.9~2.5)은 제거됨
    assert timeline.total_duration(clips) < 2.0


def test_loud_gap_keeps_tool_sound_only_in_making_preset():
    words = [W("자르고", 0.0, 0.5), W("붙여요.", 5.0, 5.5)]
    silences = [(0.5, 1.5), (4.0, 5.0)]  # 1.5~4.0 = 공구 소리
    base = {"duration": 6, "has_audio": True}
    shorts = analyze.build_plan({"words": words}, load_preset("shorts_story"), "x", base)
    making = analyze.build_plan({"words": words}, load_preset("making_process"), "x", base)
    # 두 단어를 한 문장으로 묶어 연속 처리되게 함
    for p in (shorts, making):
        p["sentences"] = [{"id": 1, "start": 0, "end": 5.5, "text": "자르고 붙여요.", "caption": "",
                           "keep": True, "reason": "", "flags": [], "words": [0, 1]}]
        p["order"] = [1]
    c1 = timeline.build_clips(shorts, load_preset("shorts_story"), silences)
    c3 = timeline.build_clips(making, load_preset("making_process"), silences)
    amb1 = [c for c in c1 if c.get("kind") == "ambient"]
    amb3 = [c for c in c3 if c.get("kind") == "ambient"]
    assert amb3 and 1.5 <= amb3[0]["src_start"] and amb3[0]["src_end"] <= 4.0
    assert pytest.approx(amb3[0]["src_end"] - amb3[0]["src_start"], abs=0.01) == 2.5
    assert len(amb1) == 1 and amb1[0]["src_end"] - amb1[0]["src_start"] <= 0.61


def test_hook_teaser_goes_first():
    words = [W("시작해요.", 0, .6), W("결국", 1.0, 1.3), W("망했어요.", 1.35, 1.9)]
    plan = analyze.build_plan({"words": words}, load_preset("shorts_story"), "x", {"duration": 3, "has_audio": True})
    plan["hook_sentence"] = 2
    clips = timeline.build_clips(plan, load_preset("shorts_story"), [])
    assert clips[0]["pass"] == "hook" and clips[0]["sentences"] == [2]
    # 본편에서도 원래 자리에 그대로 있음(시간 순서 보존)
    assert [c["sentences"] for c in clips if c["pass"] == "main"] == [[1], [2]] or \
        [c["sentences"] for c in clips if c["pass"] == "main"] == [[1, 2]]


def test_emphasis_numbers_and_terms():
    t = "샌딩은 120방부터 십오만원짜리 월넛"
    spans = keywords.emphasis_spans(t, limit=5)
    got = [t[a:b] for a, b in spans]
    assert "샌딩" in got and "120방" in got and "십오만원" in got and "월넛" in got


def test_caption_lines_within_limits():
    preset = load_preset("shorts_story")
    words = [W(x, i * .3, i * .3 + .25) for i, x in enumerate("오늘은 월넛 도마를 만들어 볼 거예요.".split())]
    plan = analyze.build_plan({"words": words}, preset, "x", {"duration": 3, "has_audio": True})
    clips = timeline.build_clips(plan, preset, [])
    caps = subtitles.build_captions(plan, clips, preset)
    for c in caps:
        lines = c["text"].split("\n")
        assert len(lines) <= preset["captions"]["max_lines"]
        assert all(len(ln) <= preset["captions"]["max_chars_per_line"] for ln in lines)
    # 마지막 짧은 말이 혼자 떨어지지 않음
    assert all(len(c["text"].replace("\n", "")) > 4 for c in caps)


def test_user_caption_edit_used():
    preset = load_preset("vlog_talk")
    words = [W("오늘은", 0, .4), W("도마를", .5, .9), W("만들어요.", 1.0, 1.5)]
    plan = analyze.build_plan({"words": words}, preset, "x", {"duration": 2, "has_audio": True})
    plan["sentences"][0]["caption"] = "오늘은 월넛 도마 만들기!"
    clips = timeline.build_clips(plan, preset, [])
    caps = subtitles.build_captions(plan, clips, preset)
    assert "월넛" in " ".join(c["text"] for c in caps)


@pytest.mark.skipif(not __import__("shutil").which("ffmpeg"), reason="ffmpeg 없음")
def test_end_to_end_render(tmp_path):
    import make_fixture
    video, tr = make_fixture.build(tmp_path / "fx")
    plan_path = pipeline.analyze(video, "shorts_story", transcript_path=tr, out_root=tmp_path / "out")
    plan = pipeline.load_plan(plan_path)
    assert plan["stats"]["retakes_removed"] == 2
    res = pipeline.apply(plan_path)
    info = media.probe(res["mp4"])
    assert (info["width"], info["height"]) == (1080, 1920)
    assert abs(info["duration"] - res["duration"]) < 0.3
    assert Path(res["srt"]).read_text(encoding="utf-8-sig").startswith("1\n")
    # 사용자 수정 → 재적용 (음성 인식 없이)
    plan["sentences"][-1]["keep"] = False
    pipeline.save_plan(plan, plan_path)
    res2 = pipeline.apply(plan_path, burn_captions=False)
    assert res2["duration"] < res["duration"]
