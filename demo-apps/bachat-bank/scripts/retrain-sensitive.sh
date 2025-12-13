#!/usr/bin/env bash
#
# DeepKernel Demo: Create Sensitive Test Model
#
# This script creates a NEW TEST model with tighter parameters.
# The ORIGINAL model is NOT modified.
#
# The test model:
# 1. Uses fewer training samples (30) -> tighter cluster
# 2. Uses synthetic "tight" baseline -> everything else is anomaly
# 3. Has suffix "_test" (e.g., bachat-bank_backend_1_test)
#
# If the test model works well, you can promote it to replace the original.
#
# Usage:
#   ./retrain-sensitive.sh                    # Create test model with synthetic data
#   ./retrain-sensitive.sh --dump <file>      # Create test model from dump file
#   ./retrain-sensitive.sh --promote          # Promote test model to original
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ML_SERVICE_URL="${ML_SERVICE_URL:-http://localhost:8081}"
CONTAINER_ID="${CONTAINER_ID:-bachat-bank_backend_1}"
PROFILE="${PROFILE:-python-fastapi}"
TEST_SUFFIX="_test"
TEST_CONTAINER_ID="${CONTAINER_ID}${TEST_SUFFIX}"

echo "============================================================"
echo "DeepKernel Demo: Create Sensitive Test Model"
echo "============================================================"
echo "Original:   $CONTAINER_ID (NOT modified)"
echo "Test Model: $TEST_CONTAINER_ID"
echo "ML Service: $ML_SERVICE_URL"
echo ""

cd "$(dirname "$SCRIPT_DIR")/../.."  # Go to repo root
cd ml-service

# Check if ML service is running
if ! curl -s "$ML_SERVICE_URL/health" | grep -q "ok"; then
    echo "ERROR: ML service not available at $ML_SERVICE_URL"
    exit 1
fi

if [ "${1:-}" = "--promote" ]; then
    # Promote test model to original
    echo "Promoting test model to original..."
    ORIGINAL_FILE="models/${CONTAINER_ID/\//_}.pkl"
    TEST_FILE="models/${TEST_CONTAINER_ID/\//_}.pkl"
    
    if [ ! -f "$TEST_FILE" ]; then
        echo "ERROR: Test model not found: $TEST_FILE"
        echo "Run this script without --promote first to create the test model."
        exit 1
    fi
    
    # Backup original
    if [ -f "$ORIGINAL_FILE" ]; then
        cp "$ORIGINAL_FILE" "${ORIGINAL_FILE}.backup"
        echo "Backed up original to ${ORIGINAL_FILE}.backup"
    fi
    
    # Copy test to original
    cp "$TEST_FILE" "$ORIGINAL_FILE"
    echo "Copied test model to original location"
    
    # Delete test model from registry
    curl -s -X DELETE "$ML_SERVICE_URL/api/ml/models/$TEST_CONTAINER_ID" > /dev/null || true
    
    echo ""
    echo "PROMOTED! The sensitive model is now the primary model."
    echo "Restart ML service or wait for next scoring request."
    exit 0
fi

if [ "${1:-}" = "--dump" ] && [ -n "${2:-}" ]; then
    # Train from dump file
    echo "Creating test model from dump file: $2"
    python3 tools/train_demo_sensitive.py \
        --container "$CONTAINER_ID" \
        --dump-file "$2" \
        --ml-service "$ML_SERVICE_URL" \
        --samples 30
else
    # Train with synthetic tight baseline
    echo "Creating test model with synthetic tight baseline"
    echo "Profile: $PROFILE"
    echo ""
    
    python3 tools/train_demo_sensitive.py \
        --container "$CONTAINER_ID" \
        --synthetic \
        --profile "$PROFILE" \
        --ml-service "$ML_SERVICE_URL" \
        --samples 30
fi

