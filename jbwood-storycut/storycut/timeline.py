"""편집 계획 → 실제 잘라 붙일 구간(clips) 목록.

clip = {"src_start", "src_end", "out_start", "out_end", "sentence_id", "pass", "words": [idx...]}
pass: "hook" (앞에 미리 보여주는 오프닝) 또는 "main"
"""
from __future__ import annotations




def _units(plan: dict) -> list[tuple[str, int, int]]:
    """출력 순서대로 (pass, sentence_id, word_idx)."""
    sents = {s["id"]: s for s in plan["sentences"]}
    words = plan["words"]
    out: list[tuple[str, int, int]] = []
    hook = plan.get("hook_sentence")
    if hook and hook in sents:
        for i in sents[hook]["words"]:
            if words[i]["keep"]:
                out.append(("hook", hook, i))
    for sid in plan["order"]:
        s = sents.get(sid)
        if not s or not s["keep"]:
            continue
        for i in s["words"]:
            if words[i]["keep"]:
                out.append(("main", sid, i))
    return out


def build_clips(plan: dict, preset: dict, silences: list[tuple[float, float]] | None = None) -> list[dict]:
    cfg = preset["cut"]
    words = plan["words"]
    n = len(words)
    duration = float(plan["media"]["duration"])
    silences = silences or []
    units = _units(plan)
    if not units:
        return []

    keep_loud = cfg.get("loud_gap_keep", 0.0)

    # 1) 이어 붙일 단어끼리 묶기
    groups: list[list[tuple[str, int, int]]] = [[units[0]]]
    ambient: list[tuple[float, float] | None] = []  # 묶음 뒤에 넣을 작업 소리 구간
    for prev, cur in zip(units, units[1:]):
        p_pass, _, pi = prev
        c_pass, _, ci = cur
        continuous = (p_pass == c_pass) and ci == pi + 1
        if continuous and words[ci]["start"] - words[pi]["end"] <= cfg["max_pause"]:
            groups[-1].append(cur)
            continue
        amb = None
        if continuous and keep_loud > 0 and silences:
            amb = loud_part(words[pi]["end"], words[ci]["start"], silences, keep_loud)
        ambient.append(amb)
        groups.append([cur])
    ambient.append(None)

    # 2) 묶음 → 원본 시간 구간 (이웃 단어를 침범하지 않게)
    clips = []
    for g, amb in zip(groups, ambient):
        f, l = g[0][2], g[-1][2]
        pad_b = max(cfg["pad_before"], cfg["keep_pause"] / 2)
        pad_a = max(cfg["pad_after"], cfg["keep_pause"] / 2)
        lo = words[f - 1]["end"] if f > 0 else 0.0
        hi = words[l + 1]["start"] if l + 1 < n else duration
        start = max(words[f]["start"] - pad_b, lo, 0.0)
        start = min(start, words[f]["start"])
        end = min(words[l]["end"] + pad_a, hi, duration)
        end = max(end, words[l]["end"])
        clips.append({
            "src_start": round(start, 3),
            "src_end": round(end, 3),
            "pass": g[0][0],
            "sentence_id": g[0][1],
            "words": [u[2] for u in g],
            "sentences": sorted({u[1] for u in g}),
        })
        if amb:
            a = max(amb[0], end)
            b = min(amb[1], hi)
            if b - a >= 0.3:
                clips.append({"src_start": round(a, 3), "src_end": round(b, 3), "pass": g[0][0],
                              "sentence_id": None, "words": [], "sentences": [], "kind": "ambient"})

    # 3) 같은 흐름에서 겹치거나 맞닿은 구간 합치기
    merged: list[dict] = []
    for c in clips:
        if merged:
            m = merged[-1]
            if m["pass"] == c["pass"] and 0 <= c["src_start"] - m["src_end"] <= 0.02:
                m["src_end"] = c["src_end"]
                m["words"] += c["words"]
                m["sentences"] = sorted(set(m["sentences"]) | set(c["sentences"]))
                continue
        merged.append(c)

    # 4) 출력 시간 계산
    t = 0.0
    for c in merged:
        c["out_start"] = round(t, 3)
        t += c["src_end"] - c["src_start"]
        c["out_end"] = round(t, 3)
    return merged


def loud_part(a: float, b: float, silences: list[tuple[float, float]], keep: float,
              min_len: float = 0.4) -> tuple[float, float] | None:
    """말 없는 구간 [a,b] 안에서 가장 긴 '시끄러운'(공구 소리 등) 부분을 최대 keep초 골라 반환."""
    loud: list[tuple[float, float]] = []
    t = a
    for s, e in sorted(silences):
        if e <= a or s >= b:
            continue
        if s > t:
            loud.append((t, min(s, b)))
        t = max(t, e)
    if t < b:
        loud.append((t, b))
    loud = [x for x in loud if x[1] - x[0] >= min_len]
    if not loud:
        return None
    s, e = max(loud, key=lambda x: x[1] - x[0])
    if e - s > keep:  # 가운데 부분을 쓴다 (소리 시작/끝의 어수선함 제외)
        mid = (s + e) / 2
        s, e = mid - keep / 2, mid + keep / 2
    return (s, e)


def total_duration(clips: list[dict]) -> float:
    return clips[-1]["out_end"] if clips else 0.0


def word_out_times(clips: list[dict], words: list[dict]) -> list[dict]:
    """출력 영상 기준 단어 시간. [{idx, pass, sentence_id, start, end}]"""
    out = []
    for c in clips:
        for i in c["words"]:
            w = words[i]
            s = max(w["start"], c["src_start"])
            e = min(w["end"], c["src_end"])
            out.append({
                "idx": i,
                "pass": c["pass"],
                "start": round(c["out_start"] + (s - c["src_start"]), 3),
                "end": round(c["out_start"] + (e - c["src_start"]), 3),
            })
    return out
