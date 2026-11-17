"""FFmpeg/FFprobe 래퍼: 영상 정보, 오디오 추출, 무음 구간 탐지."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path


class FFmpegMissing(RuntimeError):
    pass


def ffmpeg_bin(name: str = "ffmpeg") -> str:
    path = shutil.which(name)
    if not path:
        raise FFmpegMissing(
            f"{name}을(를) 찾을 수 없습니다. install.bat을 실행하거나 FFmpeg를 설치해 주세요."
        )
    return path


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def probe(path: str | Path) -> dict:
    """길이, 해상도, fps, 오디오 유무."""
    res = run([ffmpeg_bin("ffprobe"), "-v", "error", "-print_format", "json",
               "-show_format", "-show_streams", str(path)])
    if res.returncode != 0:
        raise RuntimeError(f"영상 정보를 읽을 수 없습니다: {path}\n{res.stderr}")
    data = json.loads(res.stdout)
    v = next((s for s in data["streams"] if s["codec_type"] == "video"), None)
    a = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)
    fps = 30.0
    if v and v.get("avg_frame_rate", "0/0") != "0/0":
        n, d = v["avg_frame_rate"].split("/")
        fps = float(n) / float(d) if float(d) else 30.0
    rotation = 0
    if v:
        for sd in v.get("side_data_list", []) or []:
            if "rotation" in sd:
                rotation = int(sd["rotation"])
        rotation = int(v.get("tags", {}).get("rotate", rotation))
    w, h = (v["width"], v["height"]) if v else (0, 0)
    if abs(rotation) in (90, 270):
        w, h = h, w
    return {
        "duration": float(data["format"]["duration"]),
        "width": w,
        "height": h,
        "fps": fps,
        "has_audio": a is not None,
    }


def extract_audio(src: str | Path, dst: str | Path, sr: int = 16000) -> Path:
    """음성 인식용 16kHz 모노 WAV."""
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    res = run([ffmpeg_bin(), "-y", "-i", str(src), "-vn", "-ac", "1", "-ar", str(sr),
               "-c:a", "pcm_s16le", str(dst)])
    if res.returncode != 0:
        raise RuntimeError(f"오디오 추출 실패:\n{res.stderr[-2000:]}")
    return dst


_SIL_START = re.compile(r"silence_start: (-?[\d.]+)")
_SIL_END = re.compile(r"silence_end: (-?[\d.]+)")


def detect_silence(src: str | Path, noise_db: float = -35.0, min_dur: float = 0.2) -> list[tuple[float, float]]:
    """조용한 구간 목록 [(시작, 끝)]. 말이 없지만 시끄러운 구간(공구 소리)은 여기 안 잡힘."""
    res = run([ffmpeg_bin(), "-hide_banner", "-nostats", "-i", str(src), "-vn",
               "-af", f"silencedetect=noise={noise_db}dB:d={min_dur}", "-f", "null", "-"])
    out: list[tuple[float, float]] = []
    start = None
    for line in res.stderr.splitlines():
        m = _SIL_START.search(line)
        if m:
            start = max(0.0, float(m.group(1)))
            continue
        m = _SIL_END.search(line)
        if m and start is not None:
            out.append((start, float(m.group(1))))
            start = None
    if start is not None:
        out.append((start, float("inf")))
    return out


def silent_fraction(a: float, b: float, silences: list[tuple[float, float]]) -> float:
    """[a,b] 구간 중 조용한 비율 (0~1)."""
    if b <= a:
        return 1.0
    covered = 0.0
    for s, e in silences:
        lo, hi = max(a, s), min(b, e)
        if hi > lo:
            covered += hi - lo
    return min(1.0, covered / (b - a))
