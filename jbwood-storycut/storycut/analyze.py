"""대본 분석 → 편집 계획(plan.json).

원칙
- 말한 내용을 바꾸지 않는다. 지우기(말버릇, 더듬기, 다시 말한 문장)만 한다.
- 문장 단위로 판단한다. 문장 안에서는 단어 경계에서만 자른다.
- 모든 결정에는 이유(reason)를 남겨 사용자가 확인하고 되돌릴 수 있게 한다.
"""
from __future__ import annotations

import re
from difflib import SequenceMatcher

from . import keywords

# 확실한 말버릇: 기본으로 제거
HARD_FILLERS = {"어", "음", "으", "으음", "어어", "음음", "엄", "흠", "어엄", "에", "에에", "으으", "어으"}
# 의미가 있을 수도 있는 말버릇: 기본은 '제안'만
SOFT_FILLERS = {"그", "저", "뭐", "이제", "인제", "막", "약간", "그러니까", "그니까", "좀", "저기", "그냥"}

_PUNCT = re.compile(r"[\s.,!?…~\"'“”‘’()\[\]-]+")
_SENT_END_PUNCT = re.compile(r"[.?!…]$")
# 한국어 문장 끝 어미(뒤에 쉼이 있을 때만 문장 끝으로 본다)
_SENT_END_KO = re.compile(r"(다|요|죠|까|네|지|래|자|군|걸|게)$")


def norm(text: str) -> str:
    return _PUNCT.sub("", text)


# ---------------------------------------------------------------- 문장 나누기

def split_sentences(words: list[dict], max_len: float = 12.0) -> list[list[int]]:
    """단어 인덱스 묶음 목록."""
    sents: list[list[int]] = []
    cur: list[int] = []
    for i, w in enumerate(words):
        cur.append(i)
        nxt = words[i + 1] if i + 1 < len(words) else None
        gap = (nxt["start"] - w["end"]) if nxt else 99.0
        t = w["text"].strip()
        end = False
        if _SENT_END_PUNCT.search(t):
            end = True
        elif _SENT_END_KO.search(norm(t)) and gap >= 0.35:
            end = True
        elif gap >= 1.0:
            end = True
        if end:
            sents.append(cur)
            cur = []
    if cur:
        sents.append(cur)

    # 문장 중간에 처음부터 다시 말하면("근데 처음에 재단을... 근데 처음에 재단을 잘못해서")
    # 다시 시작한 곳에서 나눈다 → 뒤에서 mark_retakes가 앞쪽을 버림
    sents = [part for s in sents for part in _split_restarts(words, s)]

    # 너무 긴 문장은 가장 긴 쉼에서 나눈다
    out: list[list[int]] = []
    stack = list(reversed(sents))
    while stack:
        s = stack.pop()
        dur = words[s[-1]]["end"] - words[s[0]]["start"]
        if dur <= max_len or len(s) < 4:
            out.append(s)
            continue
        gaps = [(words[s[k + 1]]["start"] - words[s[k]]["end"], k) for k in range(1, len(s) - 2)]
        _, k = max(gaps)
        stack.append(s[k + 1:])
        stack.append(s[:k + 1])
    return out


def _split_restarts(words: list[dict], s: list[int]) -> list[list[int]]:
    toks = [norm(words[i]["text"]) for i in s]
    content = [k for k, t in enumerate(toks) if t and t not in HARD_FILLERS]
    if len(content) < 4:
        return [s]
    a, b = toks[content[0]], toks[content[1]]
    for pos in range(2, len(content) - 1):
        j = content[pos]
        if toks[j] == a and toks[content[pos + 1]] == b:
            return [s[:j]] + _split_restarts(words, s[j:])
    return [s]


# ---------------------------------------------------------------- 단어 단위 표시

def tag_words(words: list[dict], cfg: dict) -> None:
    """각 단어에 keep/tag/reason 기록."""
    for w in words:
        w.setdefault("keep", True)
        w.setdefault("tag", "")
        w.setdefault("reason", "")
    for i, w in enumerate(words):
        n = norm(w["text"])
        if not n:
            continue
        if n in HARD_FILLERS:
            w["tag"] = "filler"
            w["reason"] = "말버릇"
            if cfg.get("remove_fillers", True):
                w["keep"] = False
        elif n in SOFT_FILLERS:
            w["tag"] = "soft_filler"
            w["reason"] = "말버릇일 수 있음(확인 필요)"
            if cfg.get("remove_soft_fillers", False):
                w["keep"] = False
        if i + 1 < len(words) and cfg.get("remove_stutter", True):
            nn = norm(words[i + 1]["text"])
            gap = words[i + 1]["start"] - w["end"]
            same = n == nn and len(n) >= 1
            partial = nn.startswith(n) and len(n) <= 2 and len(nn) > len(n)
            if (same or partial) and gap < 0.6 and n not in HARD_FILLERS:
                w["tag"] = "stutter"
                w["reason"] = f"더듬음 → '{words[i + 1]['text']}'"
                w["keep"] = False


# ---------------------------------------------------------------- 다시 말한 문장

def _similar(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    ratio = SequenceMatcher(None, a, b).ratio()
    # 앞부분이 같으면(말하다 멈추고 처음부터 다시) 강한 신호
    k = min(len(a), len(b))
    prefix = 0
    while prefix < k and a[prefix] == b[prefix]:
        prefix += 1
    if prefix >= 3 and prefix >= 0.6 * min(len(a), len(b)):
        ratio = max(ratio, 0.9)
    return ratio


def mark_retakes(sentences: list[dict], threshold: float = 0.6) -> None:
    """뒤에서 비슷한 문장을 다시 말했으면 앞 문장을 버린다(뒤가 보통 고친 버전)."""
    for i, s in enumerate(sentences):
        a = norm(s["text"])
        if len(a) < 3:
            continue
        for j in (i + 1, i + 2):
            if j >= len(sentences):
                break
            b = norm(sentences[j]["text"])
            # 한참 뒤 문장은 같은 말을 강조로 반복했을 수 있으니 20초 이내만
            if sentences[j]["start"] - s["end"] > 20:
                break
            if _similar(a, b) >= threshold:
                s["keep"] = False
                s["reason"] = f"다시 말함 → #{sentences[j]['id']} 사용"
                s["flags"].append(f"retake_of:{sentences[j]['id']}")
                break


# ---------------------------------------------------------------- 오프닝 후보

HOOK_WORDS = ["근데", "사실", "처음", "실패", "망했", "망쳤", "비밀", "몰랐", "진짜", "절대", "왜",
              "놀랐", "충격", "결국", "드디어", "완성", "문제", "실수", "버렸", "다시", "만원", "원짜리"]


def hook_candidates(sentences: list[dict], top: int = 3) -> list[dict]:
    """규칙 기반 오프닝 후보 (2단계에서 AI 분석으로 대체/보강)."""
    scored = []
    for s in sentences:
        if not s["keep"]:
            continue
        dur = s["end"] - s["start"]
        t = s["text"]
        score = 0.0
        reasons = []
        if keywords.NUM_RE.search(t):
            score += 30
            reasons.append("숫자")
        hits = [h for h in HOOK_WORDS if h in t]
        if hits:
            score += 20 + 5 * (len(hits) - 1)
            reasons.append("호기심 단어: " + ", ".join(hits[:3]))
        if "?" in t or t.rstrip().endswith(("까", "까요", "죠")):
            score += 10
            reasons.append("질문")
        if 1.2 <= dur <= 5.0:
            score += 15
            reasons.append("길이 적당")
        elif dur > 8:
            score -= 15
        if keywords.find_terms(t):
            score += 10
            reasons.append("목공 핵심어")
        if score > 0:
            scored.append({"sentence_id": s["id"], "score": round(score, 1), "text": t,
                           "start": s["start"], "end": s["end"], "why": reasons})
    scored.sort(key=lambda x: -x["score"])
    return scored[:top]


# ---------------------------------------------------------------- 계획 만들기

def build_plan(transcript: dict, preset: dict, source: str, media_info: dict,
               extra_keywords: list[str] | None = None) -> dict:
    words = [dict(w) for w in transcript["words"]]
    cfg = preset["cut"]
    tag_words(words, cfg)

    sentences = []
    for sid, idxs in enumerate(split_sentences(words), start=1):
        ws = [words[i] for i in idxs]
        text = " ".join(w["text"] for w in ws).strip()
        sentences.append({
            "id": sid,
            "start": ws[0]["start"],
            "end": ws[-1]["end"],
            "text": text,
            "caption": "",          # 비워두면 실제 발화로 자막 생성. 채우면 그 글로 자막 표시
            "keep": True,
            "reason": "",
            "flags": [],
            "words": idxs,
        })
    # 말버릇만으로 이루어진 문장은 통째로 제외
    for s in sentences:
        if all(not words[i]["keep"] or words[i]["tag"] in ("filler",) for i in s["words"]):
            s["keep"] = False
            s["reason"] = "말버릇만 있음"
    if cfg.get("remove_retakes", True):
        mark_retakes(sentences)

    plan = {
        "version": 1,
        "project": "JBWOOD AI StoryCut",
        "source": str(source),
        "media": media_info,
        "preset": preset["id"],
        "keywords": extra_keywords or [],
        "words": words,
        "sentences": sentences,
        "order": [s["id"] for s in sentences],   # 2단계: 순서 바꾸기는 이 목록만 고치면 됨
        "hook_candidates": hook_candidates(sentences),
        "hook_sentence": None,  # 오프닝으로 앞에 붙일 문장 id (사용자가 고름)
    }
    plan["stats"] = summarize(plan, preset)
    return plan


def summarize(plan: dict, preset: dict) -> dict:
    words = plan["words"]
    sents = plan["sentences"]
    kept_speech = sum(
        words[i]["end"] - words[i]["start"]
        for s in sents if s["keep"] for i in s["words"] if words[i]["keep"]
    )
    return {
        "sentences_total": len(sents),
        "sentences_kept": sum(1 for s in sents if s["keep"]),
        "retakes_removed": sum(1 for s in sents if any(f.startswith("retake_of") for f in s["flags"]) and not s["keep"]),
        "fillers_removed": sum(1 for w in words if w["tag"] == "filler" and not w["keep"]),
        "stutters_removed": sum(1 for w in words if w["tag"] == "stutter" and not w["keep"]),
        "soft_fillers_flagged": sum(1 for w in words if w["tag"] == "soft_filler"),
        "speech_seconds_kept": round(kept_speech, 1),
        "target_duration": preset["target_duration"],
    }
