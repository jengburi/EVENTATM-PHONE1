@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ==============================================
echo   JBWOOD AI StoryCut - 설치
echo ==============================================

where py >nul 2>nul
if %errorlevel%==0 (set PY=py -3) else (set PY=python)
%PY% --version >nul 2>nul
if not %errorlevel%==0 (
  echo [!] 파이썬이 없습니다. 설치를 시도합니다...
  winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements
  echo.
  echo 파이썬 설치가 끝났으면 이 창을 닫고 install.bat 을 다시 실행해 주세요.
  pause
  exit /b
)

where ffmpeg >nul 2>nul
if not %errorlevel%==0 (
  echo [!] FFmpeg가 없습니다. 설치합니다...
  winget install -e --id Gyan.FFmpeg --accept-source-agreements --accept-package-agreements
  echo FFmpeg 설치 후에는 창을 닫고 install.bat 을 한 번 더 실행해 주세요.
)

if not exist .venv (
  echo 가상환경 만드는 중...
  %PY% -m venv .venv
)
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
if not %errorlevel%==0 (
  echo [!] 패키지 설치 중 오류가 났어요. 화면 내용을 Claude에게 보내 주세요.
  pause
  exit /b 1
)
echo.
echo 설치 완료! 이제 run.bat 을 더블클릭하면 프로그램이 열립니다.
echo (처음 분석할 때 음성 인식 모델을 내려받느라 몇 분 걸릴 수 있어요)
pause
