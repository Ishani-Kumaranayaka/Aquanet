@echo off
rem Area tank program. Usage:  run-node.bat --area karapitiya --host 192.168.1.20
rem   --area  fort | karapitiya | unawatuna | hikkaduwa        --host  IP address of the main tank (server) laptop
cd /d "%~dp0.."
if not exist bin mkdir bin
dir /s /b src\*.java > sources.txt
javac -d bin @sources.txt
del sources.txt
java -cp bin aquanet.node.TankNode %*
pause
