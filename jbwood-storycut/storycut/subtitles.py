"""자막 만들기: SRT(캡컷 가져오기용) + ASS(색 강조 자막을 영상에 입히기용)."""
from __future__ import annotations

from pathlib import Path

from . import keywords
from .timeline import word_out_times


# ---------------------------------------------------------------- 자막 덩어리

def _sentence_map(plan: dict) -> dict[int, dict]:
    m = {}
    for s in plan["sentences"]:
        for i in s["words"]:
            m[i] = s
    return m


def split_lines(text: str, max_chars: int, max_lines: int) -> list[str]:
    """띄어쓰기 기준으로 줄 나누기. 한 줄이 넘치면 글자 단위로 자름."""
    words = text.split()
    lines: list[str] = []
    cur = ""
    for w in words:
        cand = (cur + " " + w).strip()
        if len(cand) <= max_chars or not cur:
            cur = cand
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    out: list[str] = []
    for ln in lines:
        while len(ln) > max_chars:
            out.append(ln[:max_chars])
            ln = ln[max_chars:]
        out.append(ln)
    return out[:max_lines] if len(out) <= max_lines else out


def build_captions(plan: dict, clips: list[dict], preset: dict) -> list[dict]:
    """[{start, end, text(줄바꿈 \n 포함)}] - 출력 영상 기준 시간."""
    cap = preset["captions"]
    max_chars, max_lines = cap["max_chars_per_line"], cap["max_lines"]
    limit = max_chars * max_lines
    words = plan["words"]
    smap = _sentence_map(plan)
    wt = word_out_times(clips, words)

    # (pass, 문장)별로 단어 묶기 - 출력 순서 유지
    blocks: list[tuple[dict, list[dict]]] = []
    for item in wt:
        s = smap[item["idx"]]
        key = (item["pass"], s["id"])
        if blocks and blocks[-1][0]["key"] == key:
            blocks[-1][1].append(item)
        else:
            blocks.append(({"key": key, "sentence": s}, [item]))

    caps: list[dict] = []
    for meta, items in blocks:
        s = meta["sentence"]
        if s.get("caption"):
            # 사용자가 고친 자막: 문장 전체 시간에 글자 수 비율로 나눠 표시
            text = s["caption"].strip()
            t0, t1 = items[0]["start"], items[-1]["end"]
            chunks = _chunk_text(text, limit)
            total = sum(len(c) for c in chunks) or 1
            t = t0
            for c in chunks:
                d = (t1 - t0) * len(c) / total
                caps.append({"start": t, "end": t + d, "text": c})
                t += d
            continue
        # 긴 쉼에서 먼저 나누고, 각 부분은 글자 수가 고르게 되도록 나눈다
        # (문장 끝 "거예요." 같은 짧은 말이 혼자 떨어지지 않게)
        runs: list[list[dict]] = [[]]
        for it in items:
            if runs[-1] and it["start"] - runs[-1][-1]["end"] > 0.6:
                runs.append([])
            runs[-1].append(it)
        for run in runs:
            for part in _balanced(run, words, limit):
                caps.append(_cap_from(part, words))

    # 줄 나누기 + 짧은 틈은 앞 자막을 늘려 깜빡임 방지
    caps.sort(key=lambda c: c["start"])
    for k, c in enumerate(caps):
        c["text"] = "\n".join(split_lines(c["text"], max_chars, max_lines))
        if k + 1 < len(caps):
            nxt = caps[k + 1]["start"]
            if 0 < nxt - c["end"] < 0.35:
                c["end"] = nxt
            c["end"] = min(c["end"], nxt)
        if c["end"] - c["start"] < 0.3:
            c["end"] = c["start"] + 0.3
    return caps


def _balanced(items: list[dict], words: list[dict], limit: int) -> list[list[dict]]:
    """단어 목록을 limit 글자 이하 덩어리로, 덩어리 길이가 최대한 비슷하게."""
    texts = [words[i["idx"]]["text"].strip() for i in items]
    total = len(" ".join(texts))
    if total <= limit:
        return [items]
    n = -(-total // limit)  # 올림
    while True:
        target = total / n
        parts, cur, cur_len = [], [], 0
        for it, t in zip(items, texts):
            add = len(t) + (1 if cur else 0)
            if cur and (cur_len + add > limit or (cur_len + add / 2 > target and len(parts) < n - 1)):
                parts.append(cur)
                cur, cur_len = [], 0
                add = len(t)
            cur.append(it)
            cur_len += add
        if cur:
            parts.append(cur)
        if len(parts) <= n or n > len(items):
            return parts
        n += 1


def _chunk_text(text: str, limit: int) -> list[str]:
    parts, cur = [], ""
    for w in text.split():
        cand = (cur + " " + w).strip()
        if len(cand) > limit and cur:
            parts.append(cur)
            cur = w
        else:
            cur = cand
    if cur:
        parts.append(cur)
    return parts or [text]


def _cap_from(items: list[dict], words: list[dict]) -> dict:
    return {
        "start": items[0]["start"],
        "end": items[-1]["end"],
        "text": " ".join(words[i["idx"]]["text"].strip() for i in items),
    }


# ---------------------------------------------------------------- SRT

def _srt_time(t: float) -> str:
    t = max(0.0, t)
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def write_srt(caps: list[dict], path: str | Path) -> Path:
    lines = []
    for k, c in enumerate(caps, start=1):
        lines += [str(k), f"{_srt_time(c['start'])} --> {_srt_time(c['end'])}", c["text"], ""]
    path = Path(path)
    # 캡컷 권장: UTF-8 (BOM 포함하면 윈도우 프로그램 호환성이 좋음)
    path.write_text("\n".join(lines), encoding="utf-8-sig")
    return path


# ---------------------------------------------------------------- ASS

def _ass_color(hex_rgb: str) -> str:
    h = hex_rgb.lstrip("#")
    r, g, b = h[0:2], h[2:4], h[4:6]
    return f"&H00{b}{g}{r}".upper()


def _ass_time(t: float) -> str:
    t = max(0.0, t)
    cs = int(round(t * 100))
    h, cs = divmod(cs, 360000)
    m, cs = divmod(cs, 6000)
    s, cs = divmod(cs, 100)
    return f"{h}:{m:02d}:{s:02d}.{cs:02d}"


def _ass_escape(t: str) -> str:
    return t.replace("\\", "＼").replace("{", "(").replace("}", ")")


def write_ass(caps: list[dict], preset: dict, path: str | Path, extra_keywords: list[str] | None = None) -> Path:
    cap = preset["captions"]
    W, H = preset["width"], preset["height"]
    normal = _ass_color(cap["normal_color"])
    emph = _ass_color(cap["emphasis_color"])
    outline = _ass_color(cap["outline_color"])
    safe = cap.get("safe_area", {})

    if cap["position"] == "center_safe":
        # 안전영역 (위 safe.top ~ 아래 safe.bottom, 오른쪽 safe.right 비움)의 가운데보다 약간 아래
        left = 60
        right = W - safe.get("right", 230)
        x = (left + right) // 2
        top, bottom = safe.get("top", 230), safe.get("bottom", 1440)
        y = int(top + (bottom - top) * 0.72)
        pos_tag = f"{{\\an5\\pos({x},{y})}}"
        align, margin_v = 5, 0
    else:
        pos_tag = ""
        align, margin_v = 2, H - safe.get("bottom", H - 60)

    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,{cap['font']},{cap['font_size']},{normal},{normal},{outline},&H80000000,1,0,0,0,100,100,0,0,1,{cap['outline']},1,{align},60,{safe.get('right', 60)},{margin_v},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events = []
    for c in caps:
        text = c["text"]
        spans = keywords.emphasis_spans(text.replace("\n", " "), extra_keywords)
        out, pos = [], 0
        for a, b in spans:
            out.append(_ass_escape(text[pos:a]))
            out.append(f"{{\\c{emph}&}}{_ass_escape(text[a:b])}{{\\c{normal}&}}")
            pos = b
        out.append(_ass_escape(text[pos:]))
        body = "".join(out).replace("\n", "\\N")
        events.append(f"Dialogue: 0,{_ass_time(c['start'])},{_ass_time(c['end'])},Default,,0,0,0,,{pos_tag}{body}")
    path = Path(path)
    path.write_text(header + "\n".join(events) + "\n", encoding="utf-8")
    return path
