@echo off
setlocal

cd /d "%~dp0"

REM ============================================================
REM UTF-8 WINDOWS
REM ============================================================

chcp 65001 >nul

REM ============================================================
REM VARIABLES D'ENVIRONNEMENT
REM ============================================================

if exist "%~dp0xiaovv-env.bat" (
    call "%~dp0xiaovv-env.bat"
)

REM ============================================================
REM VERIFICATIONS
REM ============================================================

where java >nul 2>&1

if errorlevel 1 (
    echo ERREUR : Java est introuvable.
    echo Java 21 est necessaire.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0xiaovv.jar" (
    echo ERREUR : xiaovv.jar est introuvable.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0application.properties" (
    echo ERREUR : application.properties est introuvable.
    echo.
    echo Copie application.properties.example
    echo en application.properties.
    echo.
    pause
    exit /b 1
)

REM ============================================================
REM DEMARRAGE
REM ============================================================

echo ============================================================
echo  Xiaovv V380 Server
echo ============================================================
echo.

java ^
    "-Dfile.encoding=UTF-8" ^
    "-Dsun.stdout.encoding=UTF-8" ^
    "-Dsun.stderr.encoding=UTF-8" ^
    "-Dxiaovv.config=%~dp0application.properties" ^
    -jar "%~dp0xiaovv.jar"

set EXIT_CODE=%ERRORLEVEL%

echo.
echo Xiaovv s'est arrete avec le code %EXIT_CODE%.
echo.

pause

exit /b %EXIT_CODE%