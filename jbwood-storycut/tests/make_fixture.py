"""테스트용 가짜 촬영본 만들기.

실제 목소리 대신, 대본의 단어가 나오는 시간마다 '삐' 소리를 넣은 영상을 만든다.
단어 사이 조용한 구간, 공구 소리(잡음) 구간도 넣는다.
대본(transcript.json)은 음성 인식 결과와 같은 형식으로 함께 만든다.
→ 음성 인식(Whisper)만 빼고 나머지 전 과정을 실제로 실행해 볼 수 있다.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

# (문장, 문장 앞 쉼) - 말버릇, 더듬기, 다시 말하기, 긴 침묵, 공구 소리 포함
SCRIPT = [
    ("음 오늘은 어 월넛 도마를 만들어 볼 거예요.", 0.5),
    ("이 나무 이 나무 하나가 사실 십오만원짜리예요.", 0.4),
    ("근데 처음에 재단을 재단을 잘못해서", 2.5),           # 앞에 긴 침묵
    ("근데 처음에 재단을 잘못해서 거의 버릴 뻔했어요.", 0.6),  # 다시 말함(앞 문장은 버려야 함)
    ("TOOL", 3.0),                                         # 공구 소리 3초 (말 없음)
    ("샌딩은 120방부터 400방까지 했고요.", 0.5),
    ("어", 1.5),                                           # 말버릇만 있는 문장
    ("마지막으로 오일 마감을 두 번 했습니다.", 0.5),
    ("결국 완성된 도마 보시면 결이 진짜 예쁘죠?", 0.5),
]
WORD_SEC = 0.32   # 글자 수에 비례해 단어 길이를 정함
GAP_IN = 0.08     # 단어 사이


def build(out_dir: Path) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    words = []
    beeps = []      # (start, end, freq)
    tools = []      # (start, end)
    t = 0.0
    for sent, pause in SCRIPT:
        t += pause
        if sent == "TOOL":
            tools.append((t, t + 3.0))
            t += 3.0
            continue
        toks = sent.split()
        for k, tok in enumerate(toks):
            d = max(0.18, min(0.9, WORD_SEC * len(tok.strip(".?,")) / 2 + 0.1))
            words.append({"text": tok, "start": round(t, 3), "end": round(t + d, 3), "prob": 0.95})
            beeps.append((t, t + d, 300 + (len(words) % 7) * 60))
            t += d + (GAP_IN if k < len(toks) - 1 else 0)
    total = round(t + 1.0, 3)

    # 오디오: 단어 = 사인파, 공구 = 잡음, 나머지 = 무음
    expr_parts = [f"between(t,{a:.3f},{b:.3f})*sin(2*PI*{f}*t)*0.5" for a, b, f in beeps]
    audio_expr = "+".join(expr_parts) or "0"
    tool_cond = "+".join(f"between(t,{a:.3f},{b:.3f})" for a, b in tools) or "0"
    exprfile = out_dir / "beep_expr.txt"
    exprfile.write_text(audio_expr)

    video = out_dir / "test_raw.mp4"
    filt = (
        f"[1:a]volume=0[base];"
        f"[2:a]volume='({tool_cond})*0.4':eval=frame[noise];"
        f"aevalsrc=exprs='{audio_expr}':s=48000:d={total}[beep];"
        f"[beep][noise][base]amix=inputs=3:normalize=0[aout]"
    )
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", f"testsrc2=size=1920x1080:rate=30:duration={total}",
        "-f", "lavfi", "-i", f"anullsrc=r=48000:cl=mono:d={total}",
        "-f", "lavfi", "-i", f"anoisesrc=r=48000:d={total}:a=0.5",
        "-filter_complex", filt,
        "-map", "0:v", "-map", "[aout]", "-c:v", "libx264", "-preset", "ultrafast",
        "-c:a", "aac", "-t", str(total), str(video),
    ]
    subprocess.run(cmd, check=True)
    tr = {"language": "ko", "duration": total, "words": words,
          "segments": [{"start": words[0]["start"], "end": words[-1]["end"], "text": " ".join(w["text"] for w in words)}]}
    tpath = out_dir / "test_transcript.json"
    tpath.write_text(json.dumps(tr, ensure_ascii=False, indent=1), encoding="utf-8")
    return video, tpath


if __name__ == "__main__":
    v, t = build(Path(sys.argv[1] if len(sys.argv) > 1 else "work/fixture"))
    print(v, t)
