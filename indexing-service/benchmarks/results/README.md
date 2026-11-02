# Indexing Service — Benchmark Results

## Host Environment
- **System:** `Windows 11 Home 25H2`
- **Processor:** `11th Gen Intel(R) Core(TM) i5-11400H @ 2.70GHz`
- **Python Version:** `3.14.0`

## JMH Component Microbenchmarks (`jmh-results.csv`)

| Operation | Mode | Threads | Samples | Score           | Error ± (99.9%) |
|-----------|------|---------|---------|-----------------|-----------------|
| `updateBookThroughput` | `thrpt` | 1 | 10 | 18.885523 ops/s | ± 0.683478      |
| `extractMetadata` | `avgt` | 1 | 10 | 0.001444 ms/op  | ± 0.000036      |
| `normalizeText` | `avgt` | 1 | 10 | 0.784930 ms/op  | ± 0.011507      |
| `updateBook` | `avgt` | 1 | 10 | 44.184120 ms/op | ± 7.158716      |
