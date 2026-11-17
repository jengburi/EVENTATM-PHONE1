@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist .venv (
  echo 먼저 install.bat 을 실행해 주세요.
  pause
  exit /b
)
call .venv\Scripts\activate.bat
python -m storycut.gui
if not %errorlevel%==0 pause
