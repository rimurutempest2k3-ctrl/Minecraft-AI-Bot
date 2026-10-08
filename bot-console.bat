@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if not exist "bot-common\build\libs\bot-common.jar" (
  echo Build first: gradlew.bat build
  exit /b 1
)
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -cp "bot-common\build\libs\bot-common.jar" dev.minecraftaibot.common.ExternalConsole %*
) else (
  java -cp "bot-common\build\libs\bot-common.jar" dev.minecraftaibot.common.ExternalConsole %*
)
