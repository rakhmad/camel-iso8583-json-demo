#!/usr/bin/env bash
# End-to-end demo: submits seed transactions and polls each to completion.
#
# Prerequisites:
#   Terminal 1: ./scripts/mock-switch.sh          (mock payment switch)
#   Terminal 2: ./mvnw quarkus:dev                (the bridge app)
#   Terminal 3: ./scripts/demo.sh                 (this script)
#
# Usage: ./scripts/demo.sh [BASE_URL]
#        BASE_URL defaults to http://localhost:8080/api/v1

set -euo pipefail

BASE_URL="${1:-http://localhost:8080/api/v1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SEED_DIR="$SCRIPT_DIR/seed"
POLL_MAX=30
POLL_SLEEP=1

# ── JSON helpers ──────────────────────────────────────────────────────────────

format_json() {
    if command -v jq &>/dev/null; then
        jq .
    else
        python3 -m json.tool
    fi
}

# Usage: echo "$json" | extract "fieldName"
extract() {
    local field="$1"
    if command -v jq &>/dev/null; then
        jq -r ".$field // empty"
    else
        python3 -c "import sys,json; v=json.load(sys.stdin).get(sys.argv[1],''); print('' if v is None else v)" "$field"
    fi
}

# ── App readiness ─────────────────────────────────────────────────────────────

wait_for_app() {
    echo "Waiting for app at $BASE_URL ..."
    for _ in $(seq 1 30); do
        if curl -s --connect-timeout 1 --max-time 2 \
               -o /dev/null "$BASE_URL/transactions?type=inbound" 2>/dev/null; then
            echo "App is ready."
            return 0
        fi
        sleep 1
        echo -n "."
    done
    echo ""
    echo "ERROR: App not reachable at $BASE_URL after 30s. Is ./mvnw quarkus:dev running?"
    exit 1
}

# ── Submit + poll ─────────────────────────────────────────────────────────────

submit_and_poll() {
    local label="$1"
    local payload="$2"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  $label"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  POST $BASE_URL/transactions"
    echo "  $payload" | format_json | sed 's/^/  /'
    echo ""

    SUBMIT=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/transactions" \
        -H 'Content-Type: application/json' \
        -d "$payload")
    HTTP_CODE=$(echo "$SUBMIT" | tail -1)
    BODY=$(echo "$SUBMIT" | head -n -1)

    if [ "$HTTP_CODE" != "202" ]; then
        echo "  ERROR: Expected 202, got $HTTP_CODE"
        echo "  $BODY"
        return 1
    fi

    TX_ID=$(echo "$BODY" | extract transactionId)
    echo "  202 Accepted — transactionId: $TX_ID"
    echo -n "  Polling"

    for _ in $(seq 1 $POLL_MAX); do
        sleep $POLL_SLEEP
        echo -n "."
        POLL=$(curl -s "$BASE_URL/transactions/$TX_ID")
        STATUS=$(echo "$POLL" | extract status)
        if [ "$STATUS" != "PENDING" ]; then
            echo " → $STATUS"
            echo ""
            echo "$POLL" | format_json | sed 's/^/  /'
            return 0
        fi
    done

    echo " → still PENDING after ${POLL_MAX}s"
    return 1
}

# ── Status summary ────────────────────────────────────────────────────────────

show_inbound_events() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Inbound Events (switch-initiated)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    EVENTS=$(curl -s "$BASE_URL/transactions?type=inbound")
    echo "$EVENTS" | format_json | sed 's/^/  /'
}

# ── Main ──────────────────────────────────────────────────────────────────────

wait_for_app

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  camel-iso-json  ISO 8583 ↔ JSON Bridge Demo"
echo "════════════════════════════════════════════════════════════════"

for seed in "$SEED_DIR"/*.json; do
    label=$(basename "$seed" .json | sed 's/^[0-9]*-//' | tr '-' ' ' \
        | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) tolower(substr($i,2)); print}')
    submit_and_poll "$label" "$(cat "$seed")"
done

show_inbound_events

echo ""
echo "Demo complete."
