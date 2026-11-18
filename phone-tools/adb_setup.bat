@echo off
chcp 65001 >nul
echo ==============================================
echo   휴대폰 연결 도구(ADB) 설치 + 연결 확인
echo ==============================================
echo.

where adb >nul 2>nul
if %errorlevel%==0 (
  echo [OK] ADB가 이미 설치되어 있어요.
  goto CHECK
)

echo ADB를 설치합니다. (구글 공식 Android Platform Tools)
winget install -e --id Google.PlatformTools --accept-source-agreements --accept-package-agreements
if not %errorlevel%==0 (
  echo.
  echo [!] 자동 설치에 실패했어요. 화면 내용을 캡처해서 Claude에게 보내 주세요.
  pause
  exit /b 1
)
echo.
echo 설치 완료! 이 창을 닫고 adb_setup.bat 을 한 번 더 더블클릭해 주세요.
echo (새 창에서 열어야 설치된 ADB가 인식돼요)
pause
exit /b 0

:CHECK
adb version | findstr /C:"Android Debug Bridge"
echo.
echo 휴대폰을 USB로 연결해 주세요.
echo 휴대폰에 "USB 디버깅을 허용하시겠습니까?" 창이 뜨면 [허용]을 누르세요.
echo 준비되면 아무 키나 누르세요...
pause >nul
adb start-server >nul 2>nul
adb devices -l > "%~dp0adb_result.txt"
type "%~dp0adb_result.txt"
echo.
findstr /R /C:"device product" "%~dp0adb_result.txt" >nul
if %errorlevel%==0 (
  echo [OK] 휴대폰이 연결됐어요!
  for /f "delims=" %%m in ('adb shell getprop ro.product.model') do echo      모델: %%m
  for /f "delims=" %%v in ('adb shell getprop ro.build.version.release') do echo      안드로이드: %%v
  echo.
  echo      사진/영상 폴더 개수 확인:
  adb shell "ls /sdcard/DCIM/Camera | wc -l"
  goto END
)
findstr /C:"unauthorized" "%~dp0adb_result.txt" >nul
if %errorlevel%==0 (
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
