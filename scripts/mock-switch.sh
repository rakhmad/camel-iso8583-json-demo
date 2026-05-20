#!/usr/bin/env bash
# Starts a mock ISO 8583 payment switch for local demos.
# Usage: ./scripts/mock-switch.sh [port]   (default: 8583)
#
# Run in a separate terminal BEFORE starting the app:
#   Terminal 1: ./scripts/mock-switch.sh
#   Terminal 2: ./mvnw quarkus:dev
#   Terminal 3: ./scripts/demo.sh

set -euo pipefail

PORT="${1:-8583}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

SWITCH_CLASS="target/classes/id/redhat/razhari/demo/MockSwitch.class"

if [ ! -f "$SWITCH_CLASS" ]; then
    echo "Compiling (first run only)..."
    ./mvnw -q compile -DskipTests
fi

echo "Starting mock ISO 8583 switch on port $PORT..."
./mvnw -q exec:java \
    -Dexec.mainClass="id.redhat.razhari.demo.MockSwitch" \
    -Dexec.args="$PORT"
