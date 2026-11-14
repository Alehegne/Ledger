#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TOKEN_FILE="${TOKEN_FILE:-${REPO_ROOT}/observability/prometheus/secrets/ledger_bearer_token}"

LEDGER_BASE_URL="${LEDGER_BASE_URL:-http://localhost:8080}"
LEDGER_SUPER_ADMIN_EMAIL="${LEDGER_SUPER_ADMIN_EMAIL:-super.admin@ledger.test}"
LEDGER_SUPER_ADMIN_PASSWORD="${LEDGER_SUPER_ADMIN_PASSWORD:-SuperAdmin123!}"
REFRESH_INTERVAL_SECONDS="${REFRESH_INTERVAL_SECONDS:-600}"

fetch_once() {
  mkdir -p "$(dirname "${TOKEN_FILE}")"

  local payload
  payload="$(printf '{"email":"%s","password":"%s"}' "${LEDGER_SUPER_ADMIN_EMAIL}" "${LEDGER_SUPER_ADMIN_PASSWORD}")"

  local response
  response="$(curl -fsS -X POST "${LEDGER_BASE_URL}/auth/login" \
    -H "Content-Type: application/json" \
    -d "${payload}")"

  local token
  token="$(printf '%s' "${response}" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data['token'])")"

  if [[ -z "${token}" ]]; then
    echo "Error: login succeeded but no JWT token was returned." >&2
    return 1
  fi

  local tmp_file
  tmp_file="$(mktemp "$(dirname "${TOKEN_FILE}")/.token.XXXXXX")"
  printf '%s' "${token}" > "${tmp_file}"
  chmod 644 "${tmp_file}"
  cp "${tmp_file}" "${TOKEN_FILE}"
  rm -f "${tmp_file}"

  echo "[$(date -Iseconds)] Updated Prometheus Bearer token at ${TOKEN_FILE}"
}

MODE="${1:---once}"
case "${MODE}" in
  --once)
    fetch_once
    ;;
  --watch)
    INTERVAL="${2:-${REFRESH_INTERVAL_SECONDS}}"
    echo "Watching and refreshing Prometheus Bearer token every ${INTERVAL}s (Ctrl+C to stop)..."
    while true; do
      if ! fetch_once; then
        echo "[$(date -Iseconds)] Token refresh failed; retrying in 15s..." >&2
        sleep 15
        continue
      fi
      sleep "${INTERVAL}"
    done
    ;;
  *)
    echo "Usage: $0 [--once | --watch [interval_seconds]]" >&2
    exit 1
    ;;
esac
