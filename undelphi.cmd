@echo off
rem Executa o undelphi. Sem argumentos abre a interface; "undelphi info <exe>" etc. no modo linha de comando.
setlocal
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
if not exist "%~dp0build\undelphi.args" (
  echo Projeto nao compilado. Rode build.cmd primeiro.
  exit /b 1
)
chcp 65001 >nul
"%JAVA_HOME%\bin\java.exe" -Xmx4g -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 @"%~dp0build\undelphi.args" %*
