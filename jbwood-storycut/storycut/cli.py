"""명령줄 실행 (GUI 없이). 예:

python -m storycut.cli analyze 원본.mp4 --preset shorts_story
python -m storycut.cli apply StoryCut_결과/원본/plan.json
python -m storycut.cli auto 원본.mp4 --preset vlog_talk      # 분석+적용 한 번에
"""
from __future__ import annotations

import argparse
import sys

from . import pipeline
from .config import list_presets


def _progress(p: float, msg: str) -> None:
    print(f"[{p * 100:5.1f}%] {msg}", flush=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="storycut", description="JBWOOD AI StoryCut")
    sub = ap.add_subparsers(dest="cmd", required=True)

    for name in ("analyze", "auto"):
        p = sub.add_parser(name)
        p.add_argument("video")
        p.add_argument("--preset", default="shorts_story", choices=list(list_presets()))
        p.add_argument("--model", default="medium", help="small / medium / large-v3")
        p.add_argument("--transcript", help="이미 있는 transcript.json 사용(음성 인식 건너뜀)")
        p.add_argument("--keywords", default="", help="강조할 단어, 쉼표로 구분")
        p.add_argument("--out", help="결과 폴더 (기본: 영상 옆 StoryCut_결과)")
    p = sub.add_parser("apply")
    p.add_argument("plan")
    p.add_argument("--no-burn", action="store_true", help="영상에 자막을 입히지 않음")
    sub.add_parser("presets")
    p = sub.add_parser("fetch", help="구글 드라이브 공유 링크에서 영상 내려받기")
    p.add_argument("links", nargs="+")
    p.add_argument("--out", default="inbox")

    a = ap.parse_args(argv)
    if a.cmd == "fetch":
        from .fetch import download
        for link in a.links:
            print(download(link, a.out, _progress))
        return 0
    if a.cmd == "presets":
        for k, v in list_presets().items():
            print(f"{k:16s} {v}")
        return 0
    if a.cmd in ("analyze", "auto"):
        kws = [k.strip() for k in a.keywords.split(",") if k.strip()]
        plan = pipeline.analyze(a.video, a.preset, a.model, a.transcript, kws, a.out, _progress)
        data = pipeline.load_plan(plan)
        print(f"\n편집 계획: {plan}")
        for k, v in data["stats"].items():
            print(f"  {k}: {v}")
        for w in data.get("warnings", []):
            print(f"  ! {w}")
        if data["hook_candidates"]:
            print("  오프닝 후보:")
            for h in data["hook_candidates"]:
                print(f"    #{h['sentence_id']} ({h['score']}) {h['text']}")
        if a.cmd == "analyze":
            return 0
        a.plan, a.no_burn = plan, False
    res = pipeline.apply(a.plan, _progress, burn_captions=not a.no_burn)
    print("\n완성:")
    for k, v in res.items():
        print(f"  {k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
