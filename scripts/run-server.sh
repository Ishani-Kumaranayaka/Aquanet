#!/bin/sh
cd "$(dirname "$0")/.." || exit 1
mkdir -p bin
javac -d bin $(find src -name '*.java') || exit 1
exec java -cp bin aquanet.server.AquaNetServer "$@"
