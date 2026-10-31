#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: Maven is required. Install Maven 3.8+ and add it to PATH." >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java 17+ is required." >&2
  exit 1
fi

mkdir -p benchmarks/results target
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test \
  -Dmdep.outputFile=target/test-classpath.txt

DEPENDENCIES="$(cat target/test-classpath.txt)"
CP="target/test-classes:target/classes:${DEPENDENCIES}"
# Git Bash / Cygwin needs a Windows classpath separator for the Windows JVM.
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -wp "$CP")"
fi

THREADS_LIST="${JMH_THREADS_LIST:-1 2 4}"
for threads in $THREADS_LIST; do
  echo "Running JMH indexing benchmarks with ${threads} worker thread(s)..."
  java -Xms512m -Xmx512m -cp "$CP" org.openjdk.jmh.Main \
    'com.mindsweeper.indexing.benchmark.IndexingBenchmark.*' \
    -wi 5 -i 10 -f 1 -t "$threads" -w 1s -r 1s \
    -rf csv -rff "benchmarks/results/jmh-indexing-t${threads}.csv"
done

python scripts/plot_results.py --input-dir benchmarks/results

echo "Benchmark CSV files and plots are available in benchmarks/results/."
