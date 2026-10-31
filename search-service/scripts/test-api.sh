#!/usr/bin/env bash

set -euo pipefail

BASE="${1:-http://localhost:7003}"

for path in \
    "/status" \
    "/search/filters" \
    "/search?q=adventure" \
    "/search?q=adventure&language=en&offset=0&limit=5" \
    "/search?q=adventure&author=Austen&language=en&year=1813" \
    "/search?q=nonexistenttermxyz"
do
    echo "GET $path"
    curl --fail-with-body -sS "$BASE$path"
    echo
done

echo "Checking missing 'q' parameter (expected HTTP 400):"
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/search"

echo "Checking invalid 'limit' parameter (expected HTTP 400):"
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/search?q=adventure&limit=500"
