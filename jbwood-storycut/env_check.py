"""1단계: 내 컴퓨터 환경 + 캡컷 확인 (읽기만 함, 아무것도 바꾸지 않음).

실행: 점검하기.bat 더블클릭 (또는 python env_check.py)
결과는 화면과 env_report.txt 에 저장됩니다. 이 파일 내용을 Claude에게 보내 주세요.
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

lines: list[str] = []


def say(s: str = "") -> None:
    print(s)
    lines.append(s)


def check_python() -> None:
    v = sys.version_info
    ok = (3, 9) <= (v.major, v.minor) <= (3, 13)
    say(f"[{'OK' if ok else '확인'}] 파이썬 {platform.python_version()} ({platform.architecture()[0]})")
    say(f"      운영체제: {platform.platform()}")


def check_ffmpeg() -> None:
    p = shutil.which("ffmpeg")
    if not p:
        say("[없음] FFmpeg - install.bat 이 설치를 도와줍니다")
        return
    out = subprocess.run([p, "-version"], capture_output=True, text=True).stdout.splitlines()[0]
    filt = subprocess.run([p, "-hide_banner", "-filters"], capture_output=True, text=True).stdout
    say(f"[OK]  {out}")
    say(f"[{'OK' if ' subtitles ' in filt else '확인'}] 자막 입히기 기능(libass) {'있음' if ' subtitles ' in filt else '없음 - full 빌드 필요'}")


def check_python_pkgs() -> None:
    for mod, label in [("faster_whisper", "음성 인식(faster-whisper)"), ("yaml", "설정(PyYAML)"),
                       ("tkinter", "화면(Tkinter)"), ("scenedetect", "장면 분석(2단계용)")]:
        try:
            __import__(mod)
            say(f"[OK]  {label}")
        except Exception:
            say(f"[없음] {label}")
    try:
        import ctranslate2
        n = ctranslate2.get_cuda_device_count()
        say(f"[정보] NVIDIA GPU(CUDA) {n}개 인식" + (" → 음성 인식이 빨라요" if n else " → CPU로 실행 (medium 모델 권장)"))
    except Exception:
        pass


def capcut_dirs() -> tuple[list[Path], list[Path]]:
    apps, drafts = [], []
    home = Path.home()
    if sys.platform.startswith("win"):
        la = Path(os.environ.get("LOCALAPPDATA", home / "AppData" / "Local"))
        apps += [la / "CapCut" / "Apps", Path("C:/Program Files/CapCut"), Path("C:/Program Files (x86)/CapCut")]
        drafts += [la / "CapCut" / "User Data" / "Projects" / "com.lveditor.draft"]
    elif sys.platform == "darwin":
        apps += [Path("/Applications/CapCut.app")]
        drafts += [home / "Movies" / "CapCut" / "User Data" / "Projects" / "com.lveditor.draft"]
    return apps, drafts


def check_capcut() -> None:
    say()
    say("== 캡컷 ==")
    apps, drafts = capcut_dirs()
    found = False
    for a in apps:
        if a.exists():
            found = True
            versions = sorted([p.name for p in a.iterdir() if p.is_dir() and p.name[:1].isdigit()]) if a.is_dir() else []
            say(f"[OK]  설치 위치: {a}")
            if versions:
                say(f"      버전 폴더: {', '.join(versions[-3:])}  (가장 최신: {versions[-1]})")
            plist = a / "Contents" / "Info.plist"
            if plist.exists():
                import plistlib
                info = plistlib.loads(plist.read_bytes())
                say(f"      버전: {info.get('CFBundleShortVersionString')}")
    if not found:
        say("[없음] 캡컷 설치 폴더를 찾지 못했어요 (다른 위치에 설치했을 수 있음)")

    for d in drafts:
        if not d.exists():
            say(f"[없음] 프로젝트 폴더: {d}")
            continue
        projects = [p for p in d.iterdir() if p.is_dir()]
        say(f"[OK]  프로젝트 폴더: {d}  (프로젝트 {len(projects)}개)")
        # 프로젝트 파일이 사람이 읽을 수 있는 형식인지(=자동 생성 가능성) 확인 - 읽기만 함
        checked = 0
        for p in sorted(projects, key=lambda x: x.stat().st_mtime, reverse=True)[:3]:
            for name in ("draft_content.json", "draft_info.json"):
                f = p / name
                if not f.exists():
                    continue
                head = f.read_bytes()[:200]
                plain = head.lstrip()[:1] in (b"{", b"[")
                ver = ""
                if plain:
                    try:
                        data = json.loads(f.read_text(encoding="utf-8"))
                        ver = f" / 형식버전 {data.get('version', '?')}, 앱 {data.get('last_modified_platform', {}).get('app_version', '?')}"
                    except Exception:
                        pass
                say(f"      {p.name}/{name}: {'읽을 수 있음(평문)' if plain else '암호화됨'}{ver}")
                checked += 1
        if checked == 0:
            say("      (확인할 프로젝트 파일이 없어요. 캡컷에서 빈 프로젝트를 하나 만들고 다시 실행해 주세요)")


def main() -> None:
    say("JBWOOD AI StoryCut - 환경 점검")
    say("=" * 50)
    check_python()
    check_ffmpeg()
    check_python_pkgs()
    check_capcut()
    say()
    say("이 내용을 Claude에게 보내 주시면 캡컷 연동(4단계)을 이어서 진행할게요.")
    out = Path(__file__).with_name("env_report.txt")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n저장됨: {out}")


if __name__ == "__main__":
    main()
