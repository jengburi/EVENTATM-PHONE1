"""Whisper(faster-whisper) 음성 인식 → 단어별 시간이 들어간 transcript.json.

transcript.json 형식 (다른 도구로 만든 대본도 이 형식이면 그대로 사용 가능):
{
  "language": "ko",
  "duration": 123.4,
  "words": [{"text": "오늘은", "start": 0.52, "end": 0.98, "prob": 0.97}, ...],
  "segments": [{"start": 0.52, "end": 3.1, "text": "..."}]
}
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Callable

# 말버릇을 지우지 말고 그대로 받아 적도록 유도하는 힌트.
# Whisper는 기본적으로 '어/음'을 생략하는 경향이 있다.
VERBATIM_PROMPT = "음, 어, 그러니까, 이제 오늘은, 어어, 이거 이거 재단하고 샌딩하고 마감할 거예요."


def pick_device() -> tuple[str, str]:
    try:
        import ctranslate2
        if ctranslate2.get_cuda_device_count() > 0:
            return "cuda", "float16"
    except Exception:
        pass
    return "cpu", "int8"


def load_audio(path: str | Path, sr: int = 16000):
    """FFmpeg로 16kHz 모노 float32 배열을 만든다.

    faster-whisper의 기본 디코더(PyAV)는 버전이 맞지 않으면 실패하므로
    (예: PyAV 19 + faster-whisper 1.2.1), 이미 필요한 FFmpeg로 직접 디코딩한다.
    """
    import subprocess

    import numpy as np

    from .media import ffmpeg_bin

    res = subprocess.run(
        [ffmpeg_bin(), "-nostdin", "-i", str(path), "-vn", "-ac", "1", "-ar", str(sr),
         "-f", "s16le", "-acodec", "pcm_s16le", "-"],
        capture_output=True,
    )
    if res.returncode != 0:
        raise RuntimeError("오디오를 읽을 수 없습니다:\n" + res.stderr.decode("utf-8", "replace")[-2000:])
    return np.frombuffer(res.stdout, np.int16).astype(np.float32) / 32768.0


def transcribe(audio_path: str | Path, model_size: str = "medium", language: str = "ko",
               progress: Callable[[float, str], None] | None = None) -> dict:
    from faster_whisper import WhisperModel  # 무거운 import는 필요할 때만

    device, compute = pick_device()
    if progress:
        progress(0.0, f"음성 인식 모델 불러오는 중 ({model_size}, {device}) - 처음 한 번은 내려받느라 오래 걸려요")
    model = WhisperModel(model_size, device=device, compute_type=compute)
    segments, info = model.transcribe(
        load_audio(audio_path),
        language=language,
        word_timestamps=True,
        initial_prompt=VERBATIM_PROMPT,
        vad_filter=False,          # 말버릇/짧은 소리까지 남기기 위해 VAD는 끔
        condition_on_previous_text=False,
        beam_size=5,
    )
    words, segs = [], []
    total = info.duration or 1.0
    for seg in segments:
        segs.append({"start": seg.start, "end": seg.end, "text": seg.text.strip()})
        for w in seg.words or []:
            t = w.word.strip()
            if t:
                words.append({"text": t, "start": round(w.start, 3), "end": round(w.end, 3),
                              "prob": round(w.probability, 3)})
        if progress:
            progress(min(0.99, seg.end / total), f"음성 인식 {seg.end:.0f}/{total:.0f}초")
    if progress:
        progress(1.0, "음성 인식 완료")
    return {"language": info.language, "duration": info.duration, "words": words, "segments": segs}


def save(transcript: dict, path: str | Path) -> None:
    Path(path).write_text(json.dumps(transcript, ensure_ascii=False, indent=1), encoding="utf-8")


def load(path: str | Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))
