#!/bin/bash
# 맥용: 처음 한 번은 터미널에서 chmod +x run_mac.command
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
else
  source .venv/bin/activate
fi
command -v ffmpeg >/dev/null || echo "FFmpeg가 필요합니다: brew install ffmpeg"
python -m storycut.gui
