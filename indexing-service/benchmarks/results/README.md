# Indexing benchmark results

Generated from the JMH CSV files in this directory by `scripts/plot_results.py`.
These are measurements from the machine on which the script was run; they are not portable performance guarantees.

## Host information

- OS: Windows-11-10.0.26200-SP0
- Processor: Intel64 Family 6 Model 141 Stepping 1, GenuineIntel
- Logical CPUs: 12
- JVM: see the `java -version` output recorded by the person running the benchmark.

## Average-time results

| Benchmark | Threads | Score | Unit |
|---|---:|---:|---|
| extractMetadata | 1 | 0.001675 | ms/op |
| extractMetadata | 2 | 0.001462 | ms/op |
| extractMetadata | 4 | 0.00155 | ms/op |
| normalizeText | 1 | 0.800431 | ms/op |
| normalizeText | 2 | 0.804331 | ms/op |
| normalizeText | 4 | 0.870874 | ms/op |
| updateBook | 1 | 66.3735 | ms/op |
| updateBook | 2 | 129.684 | ms/op |
| updateBook | 4 | 254.349 | ms/op |

## Throughput results

| Benchmark | Threads | Score | Unit |
|---|---:|---:|---|
| updateBookThroughput | 1 | 13.3196 | ops/s |
| updateBookThroughput | 2 | 15.8053 | ops/s |
| updateBookThroughput | 4 | 15.7071 | ops/s |

## Interpretation notes

- `updateBook` includes reading book_datalake files, metadata extraction, tokenization, posting updates, SQLite metadata persistence, and atomic JSON-index publication.
- `normalizeText` and `extractMetadata` are isolated component microbenchmarks; they do not include HTTP or datamart publication.
- The benchmark compares 1, 2, and 4 JMH worker threads on one host. The service uses a fair in-process writer lock and a local OS file lock, so datamart publications are serialized.
- Threads are not process replicas. These measurements do not demonstrate multi-process.
- Compare configurations on the same host with the same JVM heap and an otherwise idle machine. Keep the raw JMH CSV files with the report.

## Plots

- `average_time.png` (when average-time measurements are present)
- `throughput.png` (when throughput measurements are present)
