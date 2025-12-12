#!/usr/bin/env bash
set -euo pipefail

ML_HOST=${ML_HOST:-localhost}
ML_PORT=${ML_PORT:-8081}

echo "Checking ML service at http://${ML_HOST}:${ML_PORT}/health"
curl -s -D - "http://${ML_HOST}:${ML_PORT}/health"
echo

