@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
echo ==============================================
echo   휴대폰 연결 도구(ADB) 설치 + 연결 확인
echo ==============================================
echo.

call :FIND_ADB
if defined ADB goto CHECK

echo ADB를 설치합니다. (구글 공식 Android Platform Tools)
winget install -e --id Google.PlatformTools --accept-source-agreements --accept-package-agreements
rem 이미 설치되어 있어도 winget은 오류 코드를 돌려주므로, 설치 결과는 아래에서 직접 확인
call :FIND_ADB
if defined ADB goto CHECK

echo.
echo [!] ADB를 찾지 못했어요. 화면 내용을 캡처해서 Claude에게 보내 주세요.
pause
exit /b 1

:FIND_ADB
set "ADB="
for /f "delims=" %%p in ('where adb 2^>nul') do if not defined ADB set "ADB=%%p"
if defined ADB exit /b 0
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\adb.exe" set "ADB=%LOCALAPPDATA%\Microsoft\WinGet\Links\adb.exe"
if defined ADB exit /b 0
for /d %%d in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools*") do (
  if exist "%%d\platform-tools\adb.exe" set "ADB=%%d\platform-tools\adb.exe"
)
if defined ADB exit /b 0
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
exit /b 0

:CHECK
echo [OK] ADB 찾음: !ADB!
"!ADB!" version | findstr /C:"Android Debug Bridge"
echo.
echo 휴대폰을 USB로 연결해 주세요.
echo 휴대폰에 "USB 디버깅을 허용하시겠습니까?" 창이 뜨면 [허용]을 누르세요.
echo 준비되면 아무 키나 누르세요...
pause >nul
"!ADB!" start-server >nul 2>nul
"!ADB!" devices -l > "%~dp0adb_result.txt"
type "%~dp0adb_result.txt"
echo.
findstr /R /C:"device product" "%~dp0adb_result.txt" >nul
if !errorlevel!==0 (
  echo [OK] 휴대폰이 연결됐어요!
  for /f "delims=" %%m in ('"!ADB!" shell getprop ro.product.model') do echo      모델: %%m
  for /f "delims=" %%v in ('"!ADB!" shell getprop ro.build.version.release') do echo      안드로이드: %%v
  for /f "delims=" %%c in ('"!ADB!" shell "ls /sdcard/DCIM/Camera | wc -l"') do echo      카메라 폴더 파일 수: %%c
  for /f "delims=" %%s in ('"!ADB!" shell "df -h /sdcard | tail -1"') do echo      저장공간: %%s
  goto END
)
findstr /C:"unauthorized" "%~dp0adb_result.txt" >nul
if !errorlevel!==0 (
  echo [!] 휴대폰 화면에서 "USB 디버깅 허용"을 눌러 주세요. 그다음 이 파일을 다시 실행하세요.
  goto END
)
echo [!] 휴대폰이 안 보여요. 확인해 주세요:
echo     1. 개발자 옵션에서 USB 디버깅이 켜져 있는지
echo     2. 충전 전용이 아닌 데이터 전송 가능한 USB 케이블인지
echo     3. 휴대폰 알림창의 USB 설정을 "파일 전송"으로 바꿔 보기
:END
echo.
echo 이 창의 내용을 캡처해서 Claude에게 보내 주세요.
pause
