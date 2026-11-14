#!/bin/sh
# Area tank program. Usage: ./scripts/run-node.sh --area karapitiya --host 192.168.1.20
cd "$(dirname "$0")/.." || exit 1
mkdir -p bin
javac -d bin $(find src -name '*.java') || exit 1
exec java -cp bin aquanet.node.TankNode "$@"
