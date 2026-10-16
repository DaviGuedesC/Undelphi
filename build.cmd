@echo off
rem Compila, roda os testes e gera build\undelphi.args (usado pelo undelphi.cmd).
setlocal
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
call "%~dp0gradlew.bat" -p "%~dp0." build %*
