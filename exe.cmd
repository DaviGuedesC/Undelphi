@echo off
rem Gera build\exe\Undelphi\Undelphi.exe (interface) e undelphi-cli.exe (linha de comando), com o Java embutido.
setlocal
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
call "%~dp0gradlew.bat" -p "%~dp0." assemble exe %*
if errorlevel 1 exit /b 1
echo.
echo Executavel em %~dp0build\exe\Undelphi\Undelphi.exe
