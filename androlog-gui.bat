@echo off
setlocal
set "ANDROLOG_JAR=%~dp0target\androlog-gui-0.1.jar"
if not exist "%ANDROLOG_JAR%" (
    echo Build AndroLog first: run mvn package in the repository folder.
    exit /b 1
)
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -jar "%ANDROLOG_JAR%" %*
) else (
    java -jar "%ANDROLOG_JAR%" %*
)
