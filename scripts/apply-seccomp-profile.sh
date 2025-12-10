#!/usr/bin/env bash
set -euo pipefail

# Apply a generated seccomp profile to a running container (requires Docker).
# Usage: POLICY_ID=<id> CONTAINER=<name-or-id> ./scripts/apply-seccomp-profile.sh

POLICY_ID=${POLICY_ID:-}
CONTAINER=${CONTAINER:-}
POLICY_DIR=${POLICY_DIR:-/tmp/deepkernel/policies}

if [[ -z "$POLICY_ID" || -z "$CONTAINER" ]]; then
  echo "Usage: POLICY_ID=<id> CONTAINER=<name> [POLICY_DIR=/tmp/deepkernel/policies] $0"
  exit 1
fi

PROFILE="${POLICY_DIR}/${POLICY_ID}.json"
if [[ ! -f "$PROFILE" ]]; then
  echo "Profile not found: $PROFILE"
  exit 1
fi

echo "Applying seccomp profile ${PROFILE} to container ${CONTAINER}"
docker update --security-opt "seccomp=${PROFILE}" "${CONTAINER}"
echo "Done. Restart container if the runtime requires it."

