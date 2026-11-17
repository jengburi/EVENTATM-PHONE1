@echo off
chcp 65001 >nul
cd /d "%~dp0"
if exist .venv (call .venv\Scripts\activate.bat)
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
if exist .venv (set PY=python)
%PY% env_check.py
pause
