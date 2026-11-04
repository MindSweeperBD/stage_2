# Control strategy comparison

The Control Module now supports two workflows:

- **On demand:** `POST /process/{book_id}` immediately runs the pipeline for one book.
- **Periodic batch:** `POST /periodic/queue/{book_id}` persistently marks a book `PENDING`; the scheduler processes pending books every `PERIODIC_INTERVAL_SECONDS` (default `30`). `POST /periodic/run` executes a batch immediately and is intended for tests/benchmarks as well as operational troubleshooting.

## Configuration

- `PERIODIC_ENABLED`: `true` by default. Set `false` to disable scheduled runs while retaining queue/status/manual batch endpoints.
- `PERIODIC_INTERVAL_SECONDS`: positive number of seconds, default `30`.
- Existing service URL and `CONTROL_STATE_PATH` settings continue to apply.

## Quick functional test (PowerShell)

Start Ingestion, Indexing, Search, and Control. Queue a book that has not already reached `VERIFIED`:

```powershell
curl.exe -X POST http://localhost:7004/periodic/queue/1342
curl.exe http://localhost:7004/periodic/status
curl.exe -X POST http://localhost:7004/periodic/run
curl.exe http://localhost:7004/state/1342
curl.exe http://localhost:7003/books/1342
```

The queue is persisted in `data/pipeline-state.json` through the existing state repository. A failed record can be queued again; a `VERIFIED` record is not reset or reprocessed by queueing.

## Integration benchmark

Use the same number of **different, not-yet-verified IDs** for the two strategies. They should represent comparable workloads (for example, books of similar size). All four services must be running. For repeatable benchmark runs, set `PERIODIC_ENABLED=false` in the Control run configuration and restart Control; the script calls `/periodic/run` directly, so it does not need the timer and avoids a scheduled run consuming the queue at the same time. From the repository root:

```powershell
python control-module/scripts/compare_strategies.py --demand-ids 1342,1343 --periodic-ids 1344,1345 --output control-module/benchmarks/results/comparison.csv
```

Replace those example IDs with suitable IDs for your environment. The script downloads/processes books through the real services, so choose valid book IDs and allow network access for ingestion. Do not reuse IDs already in `VERIFIED` state: the on-demand strategy is idempotent and would return quickly without doing the work, invalidating the comparison.

The CSV records per-book on-demand latency, each book's final state, and summary rows for total elapsed time and throughput. Console output compares total on-demand time against periodic batch execution time and computes books/second. The periodic duration is **batch execution time only**; it excludes the scheduler's waiting time (between zero and one configured interval). For end-user freshness/latency, separately measure from queue time until the book becomes available in Search. Repeat the experiment several times and report mean and standard deviation; do not draw conclusions from a single run. Cold-cache/network effects and different book sizes can affect results.
