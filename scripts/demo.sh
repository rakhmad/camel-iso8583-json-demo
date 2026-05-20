#!/usr/bin/env bash
# Demo: polls all transactions and shows their current state.
# Seed data is injected automatically at app startup (SeedDataRoute, dev profile).
#
# Prerequisites:
#   Terminal 1: ./scripts/mock-switch.sh    (mock payment switch on port 8583)
#   Terminal 2: ./mvnw quarkus:dev          (bridge app — seed fires 3s after start)
#   Terminal 3: ./scripts/demo.sh           (this script — poll results)
#
# Usage: ./scripts/demo.sh [BASE_URL]
#        BASE_URL defaults to http://localhost:8080/api/v1

set -euo pipefail

BASE_URL="${1:-http://localhost:8080/api/v1}"
POLL_MAX=40
POLL_SLEEP=1

format_json() {
    if command -v jq &>/dev/null; then jq .; else python3 -m json.tool; fi
}

extract() {
    local field="$1"
    if command -v jq &>/dev/null; then
        jq -r ".$field // empty"
    else
        python3 -c "import sys,json; v=json.load(sys.stdin).get(sys.argv[1],''); print('' if v is None else v)" "$field"
    fi
}

wait_for_app() {
    echo "Waiting for app at $BASE_URL ..."
    for _ in $(seq 1 30); do
        if curl -s --connect-timeout 1 --max-time 2 -o /dev/null \
               "$BASE_URL/transactions?type=inbound" 2>/dev/null; then
            echo "App is ready."
            return 0
        fi
        sleep 1; echo -n "."
    done
    echo ""; echo "ERROR: App not reachable after 30s."; exit 1
}

poll_all_to_completion() {
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "  Waiting for seed transactions to complete..."
    echo "════════════════════════════════════════════════════════════════"

    for _ in $(seq 1 $POLL_MAX); do
        sleep $POLL_SLEEP
        RESULT=$(curl -s "$BASE_URL/transactions")
        COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null || echo "0")
        PENDING=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for t in d if t['status']=='PENDING'))" 2>/dev/null || echo "?")

        echo -n "  transactions=$COUNT  pending=$PENDING  "
        if [ "$COUNT" -ge 4 ] && [ "$PENDING" = "0" ] 2>/dev/null; then
            echo "— all done!"
            break
        fi
        echo ""
    done
}

show_results() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  All Transactions"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    curl -s "$BASE_URL/transactions" | format_json | sed 's/^/  /'

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Inbound Events (switch-initiated)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    curl -s "$BASE_URL/transactions?type=inbound" | format_json | sed 's/^/  /'
    echo ""
}

wait_for_app
poll_all_to_completion
show_results
echo "Demo complete."
