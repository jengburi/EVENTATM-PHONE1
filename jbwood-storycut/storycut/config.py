"""프리셋(YAML) 불러오기."""
from __future__ import annotations

from pathlib import Path

import yaml

PRESET_DIR = Path(__file__).parent / "presets"


def list_presets() -> dict[str, str]:
    """{프리셋 id: 한국어 이름}"""
    out = {}
    for p in sorted(PRESET_DIR.glob("*.yaml")):
        data = yaml.safe_load(p.read_text(encoding="utf-8"))
        out[data["id"]] = data["name"]
    return out


def load_preset(preset_id: str) -> dict:
    path = PRESET_DIR / f"{preset_id}.yaml"
    if not path.exists():
        raise ValueError(f"프리셋을 찾을 수 없습니다: {preset_id} (사용 가능: {', '.join(list_presets())})")
    return yaml.safe_load(path.read_text(encoding="utf-8"))
