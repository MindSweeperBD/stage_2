# Indexing benchmark results

No measured CSV/PNG results are bundled yet. Execute the JMH benchmarks on the group's target host; this repository does not contain simulated values.

From the project root:

```bash
bash scripts/run_benchmarks.sh
```

The script runs 1, 2 and 4 JMH worker-thread configurations (one process replica each), saves the raw JMH CSV files and generates:

- `indexing_summary.csv`
- `average_time.png`
- `throughput.png`
- this README with the actual measured scores and host information

Benchmarks cover the end-to-end `updateBook` operation (read from `book_datalake`, extract Stage 1 metadata, tokenize, update postings, write SQLite metadata and JSON index) and isolated tokenization/metadata-extraction operations. Record CPU, RAM, storage, OS, Java version and JVM settings. Thread count is not process-replica count.
