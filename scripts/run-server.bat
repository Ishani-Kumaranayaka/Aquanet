@echo off
rem Run from anywhere; compiles first if needed. Requires JDK (javac) on PATH.
cd /d "%~dp0.."
if not exist bin mkdir bin
dir /s /b src\*.java > sources.txt
javac -d bin @sources.txt
del sources.txt
java -cp bin aquanet.server.AquaNetServer %*
pause
