@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0scripts\build-android.ps1" %*
exit /b %errorlevel%
