@echo off
echo ================================================
echo  Pasang KaTv ke HP Android lewat ADB
echo ================================================
echo.
echo LANGKAH:
echo 1. HP: Settings - About phone - tap "Build number" 7x (aktifkan Developer options)
echo 2. HP: Settings - System - Developer options - aktifkan "USB debugging"
echo 3. Colok HP ke PC lewat kabel USB
echo 4. Di HP muncul prompt "Allow USB debugging" - centang + OK
echo 5. Tekan ENTER di jendela ini
echo.
pause

where adb >nul 2>nul
if %errorlevel% neq 0 (
  echo [ERROR] adb belum terinstall. Install dulu "Android SDK Platform Tools".
  echo Download: https://developer.android.com/tools/releases/platform-tools
  pause
  exit /b 1
)

adb devices
echo.
echo Cek: harus ada device dengan status "device" (bukan "unauthorized"/"offline").
pause

echo.
echo === Installing KaTv ===
adb install -r "%~dp0KaTv-release.apk"

echo.
echo === Cek hasil ===
adb shell pm list packages | findstr katv

echo.
echo Kalau muncul "package:dev.katv.tv" = BERHASIL.
echo Silakan buka aplikasi KaTv di HP lo.
pause
