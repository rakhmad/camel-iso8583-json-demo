#!/usr/bin/env bash
# Starts a mock ISO 8583 payment switch for local demos.
# Usage: ./scripts/mock-switch.sh [port]   (default: 8583)
#
# Keep this running in a separate terminal, then start the app:
#   ./mvnw quarkus:dev
# and run the demo:
#   ./scripts/demo.sh

set -euo pipefail

PORT="${1:-8583}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

echo "Building and starting mock ISO 8583 switch on port $PORT..."
./mvnw -q compile exec:java \
    -Dexec.mainClass="id.redhat.razhari.demo.MockSwitch" \
    -Dexec.args="$PORT"
