#!/usr/bin/env bash
#
# DeepKernel Demo: Restore Normal Mode
#
# Usage: ./scripts/demo-restore.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(dirname "$SCRIPT_DIR")"

echo "Restoring backend to NORMAL mode..."
export MODE=normal
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build backend
sleep 2

echo "Backend restored to normal mode"
echo ""
echo "Backend health:"
curl -s http://localhost:8000/health
echo ""

