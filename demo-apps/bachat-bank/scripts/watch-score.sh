#!/usr/bin/env bash
#
# Watch the anomaly score in real-time
#
SERVER_URL="${DK_SERVER_URL:-http://localhost:9090}"
CONTAINER="${1:-bachat-bank_backend_1}"

echo "Watching anomaly score for: $CONTAINER"
echo "Server: $SERVER_URL"
echo "Press Ctrl+C to stop"
echo ""
echo "Time                Score    Verdict"
echo "─────────────────────────────────────"

while true; do
    RESULT=$(curl -s "$SERVER_URL/api/ui/containers" | \
        python3 -c "
import json, sys
data = json.load(sys.stdin)
for c in data:
    if c.get('id') == '$CONTAINER':
        score = c.get('lastScore', 0)
        verdict = c.get('lastVerdict', 'UNKNOWN')
        print(f'{score:.4f}   {verdict}')
        break
" 2>/dev/null || echo "ERROR")
    
    echo "$(date +%H:%M:%S)          $RESULT"
    sleep 2
done

