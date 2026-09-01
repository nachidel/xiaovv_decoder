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

if not exist "%~dp0Xiaovv.exe" (
    echo ERREUR : Xiaovv.exe est introuvable.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0application.properties" (
    echo ERREUR : application.properties est introuvable.
    echo.
    echo Copie application.properties.example
    echo en application.properties,
    echo puis configure tes cameras.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0app" (
    echo ERREUR : le dossier app est introuvable.
    echo.
    pause
    exit /b 1
)

REM ============================================================
REM CONFIGURATION
REM ============================================================
REM
REM Xiaovv.exe lit app\application.properties.
REM On recopie donc la configuration externe avant le lancement.
REM

copy /Y ^
    "%~dp0application.properties" ^
    "%~dp0app\application.properties" ^
    >nul

if errorlevel 1 (
    echo ERREUR : impossible de copier application.properties.
    echo.
    pause
    exit /b 1
)

REM ============================================================
REM DEMARRAGE
REM ============================================================

echo ============================================================
echo  Xiaovv V380 Server
echo  Windows autonome - Java embarque
echo ============================================================
echo.

"%~dp0Xiaovv.exe"

set EXIT_CODE=%ERRORLEVEL%

echo.
echo Xiaovv s'est arrete avec le code %EXIT_CODE%.
echo.

pause

exit /b %EXIT_CODE%
