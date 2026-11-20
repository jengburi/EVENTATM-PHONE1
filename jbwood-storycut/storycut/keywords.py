"""자막 강조 단어 찾기."""
from __future__ import annotations

import re

# 숫자 + 단위 (한글 숫자 포함)
NUM_RE = re.compile(
    r"(\d[\d,.]*\s*(?:mm|cm|m|T|t|만원|천원|원|개|시간|분|초|일|번|배|방|%|퍼센트|도|kg|g|년)?)"
    r"|((?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무)\s*(?:개|시간|분|번|배|장|달|년|만원|원|방))"
    r"|((?:[일이삼사오육칠팔구]?(?:십|백|천|만))+[일이삼사오육칠팔구]?(?:만원|천원|원|방|개|년|시간|분))"
)

# 목공 핵심어 (자유롭게 추가하세요)
WOOD_TERMS = [
    "원목", "월넛", "호두나무", "오크", "참나무", "메이플", "단풍나무", "체리", "애쉬", "물푸레",
    "편백", "삼나무", "소나무", "자작", "합판", "집성목", "MDF",
    "재단", "샌딩", "사포", "대패", "수압대패", "자동대패", "테이블쏘", "각도절단기", "직소", "트리머",
    "라우터", "끌", "톱", "도미노", "비스킷", "도웰", "장부", "짜맞춤", "주먹장", "클램프", "본드",
    "오일", "마감", "바니쉬", "우레탄", "스테인", "도장", "결", "옹이", "뒤틀림", "갈라짐",
    "도마", "트레이", "스툴", "의자", "테이블", "선반", "수납장", "완성", "완성품", "실패", "주문", "제작",
]


def find_terms(text: str, extra: list[str] | None = None) -> list[str]:
    terms = WOOD_TERMS + (extra or [])
    return [t for t in terms if t and t in text]


def emphasis_spans(text: str, extra: list[str] | None = None, limit: int = 2) -> list[tuple[int, int]]:
    """강조할 글자 범위 [(시작, 끝)]. 한 자막에 최대 limit개."""
    spans: list[tuple[int, int]] = []
    for m in NUM_RE.finditer(text):
        if m.group(0).strip():
            spans.append((m.start(), m.end()))
    for t in sorted(set((extra or []) + WOOD_TERMS), key=len, reverse=True):
        start = 0
        while t and (k := text.find(t, start)) >= 0:
            spans.append((k, k + len(t)))
            start = k + len(t)
    # 겹치는 범위 정리: 먼저 나온 것 우선, 사용자 키워드/숫자 우선순위는 위 순서
    spans.sort(key=lambda s: (s[0], -(s[1] - s[0])))
    merged: list[tuple[int, int]] = []
    for s in spans:
        if merged and s[0] < merged[-1][1]:
            continue
        merged.append(s)
    # 사용자 키워드가 있으면 그것을 먼저 남김
    if extra:
        pri = [s for s in merged if text[s[0]:s[1]] in extra]
        rest = [s for s in merged if s not in pri]
        merged = (pri + rest)[:limit]
        merged.sort()
        return merged
    return merged[:limit]
