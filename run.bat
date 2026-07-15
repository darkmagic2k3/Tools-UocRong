@echo off
setlocal
REM Chay Map Editor. Tu dong tim JDK doi cao nhat (>=21) trong may.
REM jar build bang Java 21 -> can JDK >= 21.

set "JAVA_EXE="
for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$b=$null; $roots=@('Java','Eclipse Adoptium','Microsoft','Zulu','Amazon Corretto','BellSoft','Semeru'); foreach($p in @($env:ProgramFiles, ${env:ProgramFiles(x86)})){ if($p){ foreach($v in $roots){ $r=Join-Path $p $v; if(Test-Path $r){ Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | ForEach-Object { $e=Join-Path $_.FullName 'bin\java.exe'; if(Test-Path $e){ $o=(& $e -version 2>&1 | Out-String); if($o -match 'version .(\d+)'){ $m=[int]$Matches[1]; if($m -ge 21 -and (-not $b -or $m -gt $b.M)){ $b=[pscustomobject]@{E=$e;M=$m} } } } } } } } }; if($b){ $b.E }"`) do set "JAVA_EXE=%%J"

REM Fallback: JAVA_HOME neu hop le
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not defined JAVA_EXE (
  echo [run.bat] Khong tim thay JDK ^>= 21 trong may.
  echo [run.bat] Hay cai JDK 21+ ^(vd Temurin/Adoptium^) roi chay lai, hoac set JAVA_HOME.
  pause
  exit /b 1
)

echo [run.bat] Dung JDK: "%JAVA_EXE%"
"%JAVA_EXE%" -jar "%~dp0target\map-editor.jar" %*
pause
