#!/usr/bin/env bash
#
# DeepKernel Demo: Simulate THREAT Detection
#
# This script enables ML score simulation to demonstrate threat detection
# WITHOUT needing to actually inject anomalous syscalls.
#
# Usage:
#   ./simulate-threat.sh                    # Enable threat simulation
#   ./simulate-threat.sh --disable          # Disable simulation
#   ./simulate-threat.sh --score -0.95      # Custom score
#
set -euo pipefail

ML_SERVICE_URL="${ML_SERVICE_URL:-http://localhost:8081}"
CONTAINER_ID="${CONTAINER_ID:-bachat-bank_backend_1}"
SCORE="${SCORE:--0.85}"
ACTION="${1:-enable}"

echo "============================================================"
echo "DeepKernel Demo: Anomaly Score Simulation"
echo "============================================================"
echo "ML Service: $ML_SERVICE_URL"
echo "Container:  $CONTAINER_ID"
echo ""

case "$ACTION" in
    --disable|disable|off)
        echo "Disabling simulation for $CONTAINER_ID..."
        curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate/disable?container_id=$CONTAINER_ID" | python3 -m json.tool
        echo ""
        echo "Simulation DISABLED - Real ML scores will be used"
        ;;
    
    --disable-all|disable-all)
        echo "Disabling ALL simulations..."
        curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate/disable" | python3 -m json.tool
        echo ""
        echo "ALL simulations DISABLED"
        ;;
    
    --status|status)
        echo "Current simulation status:"
        curl -s "$ML_SERVICE_URL/api/demo/simulate/status" | python3 -m json.tool
        ;;
    
    --score)
        SCORE="${2:--0.85}"
        echo "Enabling THREAT simulation with score: $SCORE"
        echo ""
        curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate?container_id=$CONTAINER_ID&score=$SCORE&anomalous=true" | python3 -m json.tool
        echo ""
        echo "============================================================"
        echo "SIMULATION ENABLED!"
        echo ""
        echo "All scoring requests for $CONTAINER_ID will now return:"
        echo "  - Score: $SCORE (highly anomalous)"
        echo "  - Anomalous: true"
        echo "  - Verdict: THREAT (via triage)"
        echo ""
        echo "The next window from the agent will trigger THREAT detection!"
        echo "Watch the UI at http://<server>:9090"
        echo "============================================================"
        ;;
    
    enable|--enable|*)
        echo "Enabling THREAT simulation with score: $SCORE"
        echo ""
        curl -s -X POST "$ML_SERVICE_URL/api/demo/simulate?container_id=$CONTAINER_ID&score=$SCORE&anomalous=true" | python3 -m json.tool
        echo ""
        echo "============================================================"
        echo "SIMULATION ENABLED!"
        echo ""
        echo "All scoring requests for $CONTAINER_ID will now return:"
        echo "  - Score: $SCORE (highly anomalous)"
        echo "  - Anomalous: true"  
        echo "  - Verdict: THREAT (via triage)"
        echo ""
        echo "The next window from the agent will trigger THREAT detection!"
        echo "Watch the UI at http://<server>:9090"
        echo ""
        echo "To disable: $0 --disable"
        echo "============================================================"
        ;;
esac

