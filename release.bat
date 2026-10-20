@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\release.ps1" %*
set "release_result=%ERRORLEVEL%"
echo.
if not "%release_result%"=="0" echo Pubblicazione non completata. Leggi il messaggio qui sopra.
pause
exit /b %release_result%
