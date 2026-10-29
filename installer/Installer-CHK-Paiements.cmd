@echo off
setlocal EnableExtensions
title Installation CHK Paiements - serveur independant
echo Installation du module CHK Paiements.
echo Le serveur universel et son URL /mcp ne seront pas modifies.
set "INSTALLER=%TEMP%\CHK-Paiements-Installer.ps1"
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/Chasmet/Chk-paiment-/40f1f58fbd48b66546fea8326a22b4efb2732b26/installer/Installer-CHK-Paiements.ps1' -UseBasicParsing -OutFile '%INSTALLER%'"
if errorlevel 1 (
  echo Echec telechargement du programme.
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%INSTALLER%"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" echo L'installation est incomplete, voir le message ci-dessus.
echo Appuyer sur une touche pour fermer.
pause >nul
exit /b %RESULT%
