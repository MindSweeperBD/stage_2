# Search Service — Benchmark Results

## Host Environment
- **System:** `Windows-11-10.0.26100-SP0`
- **Processor:** `AMD64 Family 23 Model 8 Stepping 2, AuthenticAMD`
- **Python Version:** `3.14.0`

## 1. JMH Component Microbenchmarks (`jmh-results.csv`)

| Operation | Mode | Threads | Samples | Score (ms/op) | Error ± (99.9%) |
|-----------|------|---------|---------|---------------|-----------------|
| `filteredSearch` | `avgt` | 1 | 10 | 3.2776 | ± 0.5028 |
| `keywordSearch` | `avgt` | 1 | 10 | 7.5634 | ± 0.8148 |
| `tokenizeQuery` | `avgt` | 1 | 10 | 0.0024 | ± 0.0003 |

## 2. Concurrent HTTP Thread Scaling (`concurrency-results.csv`)

| Client Threads | Total Requests | Avg Latency (ms) | p95 Latency (ms) | Throughput (req/s) |
|----------------|----------------|------------------|------------------|--------------------|
| 1 | 40 | 14.905 | 20.046 | 66.49 |
| 2 | 80 | 8.731 | 14.734 | 217.15 |
| 4 | 160 | 8.736 | 16.586 | 431.20 |
| 8 | 320 | 10.880 | 27.658 | 647.74 |
