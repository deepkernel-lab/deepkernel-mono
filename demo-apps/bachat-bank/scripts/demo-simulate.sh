#!/usr/bin/env bash
#
# DeepKernel Demo: Full Simulation Flow
#
# This script demonstrates the complete threat detection pipeline
# using ML score simulation (no real syscall injection needed).
#
# Flow:
# 1. Show current normal state (SAFE)
# 2. Enable threat simulation
# 3. Wait for next scoring window
# 4. Show THREAT detection in UI
# 5. Disable simulation
#
set -euo pipefail

ML_SERVICE_URL="${ML_SERVICE_URL:-http://localhost:8081}"
DK_SERVER_URL="${DK_SERVER_URL:-http://localhost:9090}"
CONTAINER_ID="${CONTAINER_ID:-bachat-bank_backend_1}"

echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║           DeepKernel Demo: Threat Detection Simulation           ║"
echo "╠═══════════════════════════════════════════════════════════════════╣"
echo "║  ML Service:  $ML_SERVICE_URL"
echo "║  DK Server:   $DK_SERVER_URL"
echo "║  Container:   $CONTAINER_ID"
echo "╚═══════════════════════════════════════════════════════════════════╝"
echo ""

# Check services
echo "[1/5] Checking services..."
if ! curl -s "$ML_SERVICE_URL/health" | grep -q "ok"; then
    echo "ERROR: ML service not available at $ML_SERVICE_URL"
    exit 1
fi
if ! curl -s "$DK_SERVER_URL/api/health" | grep -q "ok"; then
    echo "ERROR: DeepKernel server not available at $DK_SERVER_URL"
    exit 1
fi
echo "      Services OK"
echo ""

# Show current state
echo "[2/5] Current container state:"
curl -s "$DK_SERVER_URL/api/ui/containers" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for c in data:
    if c.get('id') == '$CONTAINER_ID':
        print(f\"      Container: {c['id']}\")
        print(f\"      Score:     {c.get('lastScore', 'N/A')}\")
        print(f\"      Verdict:   {c.get('lastVerdict', 'N/A')}\")
        print(f\"      Model:     {c.get('modelStatus', 'N/A')}\")
        break
"
echo ""

# Enable simulation
echo "[3/5] Enabling THREAT simulation..."
echo "      Setting score to -0.85 (highly anomalous)"
curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate?container_id=$CONTAINER_ID&score=-0.85&anomalous=true" > /dev/null
echo "      Simulation ENABLED"
echo ""

# Wait for next window
echo "[4/5] Waiting for next scoring window (5-10 seconds)..."
echo "      The agent sends windows every 5 seconds."
echo "      Watch the UI for the score change!"
echo ""
echo "      Polling for THREAT detection..."

for i in {1..12}; do
    sleep 5
    VERDICT=$(curl -s "$DK_SERVER_URL/api/ui/containers" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for c in data:
    if c.get('id') == '$CONTAINER_ID':
        print(c.get('lastVerdict', 'UNKNOWN'))
        break
" 2>/dev/null || echo "UNKNOWN")
    
    SCORE=$(curl -s "$DK_SERVER_URL/api/ui/containers" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for c in data:
    if c.get('id') == '$CONTAINER_ID':
        print(f\"{c.get('lastScore', 0):.2f}\")
        break
" 2>/dev/null || echo "0.00")
    
    echo "      [$i/12] Score: $SCORE, Verdict: $VERDICT"
    
    if [ "$VERDICT" = "THREAT" ]; then
        echo ""
        echo "╔═══════════════════════════════════════════════════════════════════╗"
        echo "║                    THREAT DETECTED!                               ║"
        echo "╠═══════════════════════════════════════════════════════════════════╣"
        echo "║  The simulated anomaly score triggered:                          ║"
        echo "║  - ML Model flagged window as ANOMALOUS                          ║"
        echo "║  - Triage determined verdict: THREAT                             ║"
        echo "║  - Policy engine would generate Seccomp policy                   ║"
        echo "╚═══════════════════════════════════════════════════════════════════╝"
        break
    fi
done

echo ""

# Disable simulation
echo "[5/5] Disabling simulation..."
curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate/disable?container_id=$CONTAINER_ID" > /dev/null
echo "      Simulation DISABLED"
echo ""

echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║                     DEMO COMPLETE                                 ║"
echo "╠═══════════════════════════════════════════════════════════════════╣"
echo "║  The simulation showed how DeepKernel detects anomalies:         ║"
echo "║                                                                   ║"
echo "║  1. Agent captures syscall windows                               ║"
echo "║  2. ML model scores each window                                  ║"
echo "║  3. Anomalous scores trigger triage                              ║"
echo "║  4. Triage determines THREAT/SAFE verdict                        ║"
echo "║  5. THREAT triggers policy generation                            ║"
echo "║                                                                   ║"
echo "║  Next windows will return to normal scoring (simulation off)     ║"
echo "╚═══════════════════════════════════════════════════════════════════╝"

