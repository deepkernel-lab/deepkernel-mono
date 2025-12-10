#!/usr/bin/env bash
set -euo pipefail

MODE=${1:-}
if [[ -z "${MODE}" ]]; then
  echo "usage: MODE=normal|malicious|safe ./scripts/set-mode.sh"
  exit 1
fi

export MODE
echo "Setting backend MODE=${MODE} and restarting..."
docker-compose -f "$(dirname "$0")/../docker-compose.yml" up -d --build backend

