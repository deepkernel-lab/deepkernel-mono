#!/usr/bin/env bash
set -euo pipefail

# Test script to verify LLM Triage capabilities live.
# Usage: ./scripts/test-triage-live.sh

echo "--- Testing Gemini Triage Adapter ---"

# 1. Set Change Context (Mocking a SAFE change)
echo "[1] Setting Change Context (SAFE scenario)..."
CONTAINER_ID="test-container-safe" \
COMMIT_ID="safe123" \
REPO_URL="https://github.com/test/repo" \
CHANGED_FILES="backend/api.py" \
DIFF_SUMMARY="Added internal health check endpoint" \
./scripts/set-change-context.sh

# 2. Trigger Triage (SAFE)
echo "[2] Triggering Triage (SAFE scenario)..."
./scripts/triage-test.sh | grep -E '"verdict"|"explanation"'

# 3. Trigger Triage (THREAT) - No context
echo "[3] Triggering Triage (THREAT scenario - High Score, No Context)..."
# We use a new container ID so it doesn't match the safe context
CONTAINER_ID="test-container-threat" \
ML_SCORE=0.98 \
IS_ANOMALOUS=true \
SYSCALL_SUMMARY="record_count=200\ntop_syscalls=connect:100, execve:5\nsequence_sample=execve → connect → connect" \
DIFF_SUMMARY="" \
./scripts/triage-test.sh | grep -E '"verdict"|"explanation"'

echo "--- Done ---"

