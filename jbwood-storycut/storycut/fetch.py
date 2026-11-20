"""구글 드라이브 공유 링크에서 영상 내려받기.

링크 공유가 '링크가 있는 모든 사용자'로 되어 있어야 합니다.
예) python -m storycut.cli fetch "https://drive.google.com/file/d/<ID>/view?usp=sharing" --out inbox
"""
from __future__ import annotations

import re
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

_ID_PATTERNS = [
    re.compile(r"/file/d/([A-Za-z0-9_-]{10,})"),
    re.compile(r"/open\?id=([A-Za-z0-9_-]{10,})"),
]


def drive_id(link: str) -> str:
    for p in _ID_PATTERNS:
        m = p.search(link)
        if m:
            return m.group(1)
    q = parse_qs(urlparse(link).query)
    if "id" in q:
        return q["id"][0]
    if re.fullmatch(r"[A-Za-z0-9_-]{10,}", link):
        return link
    raise ValueError("구글 드라이브 파일 링크가 아니에요. '공유 → 링크 복사'로 받은 주소를 넣어 주세요.")


def _filename(headers, fallback: str) -> str:
    cd = headers.get("Content-Disposition", "") or ""
    m = re.search(r"filename\*=UTF-8''([^;]+)", cd) or re.search(r'filename="([^"]+)"', cd)
    if m:
        from urllib.parse import unquote
        return Path(unquote(m.group(1))).name
    return fallback


def download(link: str, out_dir: str | Path, progress=None) -> Path:
    fid = drive_id(link)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    # confirm=t: 큰 파일의 '바이러스 검사를 할 수 없습니다' 확인 페이지를 건너뜀
    url = f"https://drive.usercontent.google.com/download?id={quote(fid)}&export=download&confirm=t"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (StoryCut)"})
    try:
        r = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            raise RuntimeError("파일을 찾을 수 없어요. 링크가 맞는지, 공유가 '링크가 있는 모든 사용자'인지 확인해 주세요.") from None
        raise
    with r:
        ctype = r.headers.get("Content-Type", "")
        if "text/html" in ctype:
            raise RuntimeError("파일을 받을 수 없어요. 드라이브에서 공유를 '링크가 있는 모든 사용자'로 바꿨는지 확인해 주세요.")
        name = _filename(r.headers, f"{fid}.mp4")
        total = int(r.headers.get("Content-Length") or 0)
        dst = out_dir / name
        done = 0
        with open(dst, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if progress and total:
                    progress(done / total, f"내려받는 중 {done >> 20}/{total >> 20}MB")
    if progress:
        progress(1.0, f"내려받기 완료: {dst.name}")
    return dst
