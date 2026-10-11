@echo off
where python >nul 2>nul
if errorlevel 1 (
  echo Python 3.9+ is required. Install it from python.org ^(tick "Add python.exe to PATH"^) and run this again.
  pause
  exit /b 1
)
python -m pip install --upgrade pyinstaller
python -m PyInstaller --onefile --name SteamStreamHost steamstream_host.py
echo.
echo Done. Your exe is at: dist\SteamStreamHost.exe
pause
