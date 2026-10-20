@echo off
setlocal
cd /d "%~dp0"
if not exist ".runtime\apache-maven-3.9.11\bin\mvn.cmd" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\bootstrap.ps1" -MavenOnly
  if errorlevel 1 exit /b 1
)
call ".runtime\apache-maven-3.9.11\bin\mvn.cmd" "-Dmaven.repo.local=%~dp0.runtime\m2" %*
exit /b %ERRORLEVEL%
