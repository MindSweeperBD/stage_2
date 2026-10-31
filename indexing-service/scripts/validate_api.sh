#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${INDEXING_BASE_URL:-http://localhost:7002}"
BOOK_ID="${BOOK_ID:-1342}"

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required." >&2; exit 1; }

printf '\n[1/4] Health check\n'
curl --fail --silent --show-error "$BASE_URL/status"

printf '\n\n[2/4] Index one book (it must already be ingested)\n'
curl --fail --silent --show-error -X POST "$BASE_URL/index/update/$BOOK_ID"

printf '\n\n[3/4] Read indexing status\n'
curl --fail --silent --show-error "$BASE_URL/index/status"

printf '\n\n[4/4] Rebuild the full index\n'
curl --fail --silent --show-error -X POST "$BASE_URL/index/rebuild"
printf '\n\nAPI smoke checks completed.\n'
