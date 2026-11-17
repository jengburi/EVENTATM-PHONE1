"""FFmpeg로 컷 편집 + 화면 비율 맞추기 + 자막 입히기."""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Callable

from .media import ffmpeg_bin

FADE = 0.012  # 컷마다 아주 짧은 오디오 페이드(딸깍 소리 방지)


def _reframe(preset: dict) -> str:
    W, H = preset["width"], preset["height"]
    if preset.get("reframe") == "center_crop":
        return f"scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},setsar=1"
    return (f"scale={W}:{H}:force_original_aspect_ratio=decrease,"
            f"pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1")


def build_filter(clips: list[dict], preset: dict, has_audio: bool, ass_name: str | None) -> str:
    parts = []
    labels = []
    for k, c in enumerate(clips):
        a, b = c["src_start"], c["src_end"]
        d = b - a
        parts.append(f"[0:v]trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS[v{k}]")
        if has_audio:
            fo = max(0.0, d - FADE)
            parts.append(
                f"[0:a]atrim=start={a:.3f}:end={b:.3f},asetpts=PTS-STARTPTS,"
                f"afade=t=in:st=0:d={FADE},afade=t=out:st={fo:.3f}:d={FADE}[a{k}]"
            )
            labels.append(f"[v{k}][a{k}]")
        else:
            labels.append(f"[v{k}]")
    n = len(clips)
    if has_audio:
        parts.append(f"{''.join(labels)}concat=n={n}:v=1:a=1[vc][aout]")
    else:
        parts.append(f"{''.join(labels)}concat=n={n}:v=1:a=0[vc]")
    vf = _reframe(preset)
    if ass_name:
        vf += f",subtitles={ass_name}"
    parts.append(f"[vc]{vf},format=yuv420p[vout]")
    return ";\n".join(parts)


def render(source: str | Path, clips: list[dict], preset: dict, out_mp4: str | Path,
           has_audio: bool = True, ass_path: str | Path | None = None,
           progress: Callable[[float, str], None] | None = None, total: float | None = None) -> Path:
    if not clips:
        raise ValueError("남길 구간이 없습니다. 편집 계획에서 문장을 하나 이상 선택해 주세요.")
    out_mp4 = Path(out_mp4).resolve()
    workdir = out_mp4.parent
    workdir.mkdir(parents=True, exist_ok=True)
    # 자막 파일은 작업 폴더 기준 상대 경로로 넘긴다 (윈도우 경로의 ':' 문제 회피)
    ass_name = None
    if ass_path:
        ass_path = Path(ass_path).resolve()
        ass_name = ass_path.name if ass_path.parent == workdir else str(ass_path).replace("\\", "/").replace(":", "\\:")
    script = workdir / (out_mp4.stem + ".filter.txt")
    script.write_text(build_filter(clips, preset, has_audio, ass_name), encoding="utf-8")

    cmd = [ffmpeg_bin(), "-y", "-hide_banner", "-i", str(Path(source).resolve()),
           "-filter_complex_script", script.name, "-map", "[vout]"]
    if has_audio:
        cmd += ["-map", "[aout]", "-c:a", "aac", "-b:a", "192k"]
    cmd += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", str(out_mp4)]

    proc = subprocess.Popen(cmd, cwd=workdir, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace")
    assert proc.stdout is not None
    for line in proc.stdout:
        if progress and total and line.startswith("out_time_ms="):
            try:
                t = int(line.split("=")[1]) / 1_000_000
                progress(min(0.99, t / total), f"영상 만드는 중 {t:.0f}/{total:.0f}초")
            except ValueError:
                pass
    err = proc.stderr.read() if proc.stderr else ""
    if proc.wait() != 0:
        raise RuntimeError(f"영상 만들기 실패:\n{err[-3000:]}")
    if progress:
        progress(1.0, "영상 완성")
    return out_mp4
