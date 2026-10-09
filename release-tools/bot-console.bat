@echo off
setlocal
chcp 65001 >nul
set "BOT_ENDPOINT=%~1"
if not defined BOT_ENDPOINT set "BOT_ENDPOINT=%~dp0bot-console.properties"
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -cp "%~dp0tools\bot-console.jar" dev.minecraftaibot.common.ExternalConsole "%BOT_ENDPOINT%"
) else (
  java -cp "%~dp0tools\bot-console.jar" dev.minecraftaibot.common.ExternalConsole "%BOT_ENDPOINT%"
)
