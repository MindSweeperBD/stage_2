#!/usr/bin/env bash

set -euo pipefail

BASE="${1:-http://localhost:7001}"

for path in \
    /status \
    /ingest/list \
    /ingest/stats \
    /ingest/status/1342
do
    echo "GET $path"

    curl --fail-with-body -sS "$BASE$path"

    echo
done

echo "POST /ingest/1342"
echo "This requires internet access to Gutenberg."

curl --fail-with-body -sS \
    -X POST "$BASE/ingest/1342"

echo

echo "Checking invalid book ID (expected HTTP 400):"

curl -s \
    -o /dev/null \
    -w '%{http_code}\n' \
    "$BASE/ingest/status/not-a-number"
