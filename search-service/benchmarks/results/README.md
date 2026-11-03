# Search Service — Benchmark Results

This document summarizes the performance evaluation of the **Search Service (`com.mindsweeper.search`)** across two complementary dimensions required by Stage 2:
1. **Internal algorithmic latency** (isolated from HTTP networking) measured with **JMH 1.37**.
2. **End-to-end HTTP concurrency and throughput scaling** under simultaneous client threads measured against a live Javalin/Jetty server instance.

## Host Environment
- **System:** `Windows-11-10.0.26100-SP0`
- **Processor:** `AMD64 Family 23 Model 8 Stepping 2, AuthenticAMD`
- **Python Version:** `3.14.0`

---

## 1. JMH Component Microbenchmarks (`jmh-results.csv`)

This benchmark evaluates the internal methods of `SearchEngine` and `DatamartStore` over a synthetic datamart of **500 indexed books** (with 5 warmup iterations and 10 measurement iterations of 1 second each). It measures the average execution time per operation (`avgt` in `ms/op`) and the 99.9% confidence interval (`Error ±`).

| Operation | Mode | Threads | Samples | Score (ms/op) | Error ± (99.9%) |
|-----------|------|---------|---------|---------------|-----------------|
| `filteredSearch` | `avgt` | 1 | 10 | 3.3182 | ± 0.4456 |
| `keywordSearch` | `avgt` | 1 | 10 | 7.9776 | ± 1.0048 |
| `tokenizeQuery` | `avgt` | 1 | 10 | 0.0022 | ± 0.0001 |

### What this table shows:
- **`tokenizeQuery`:** Measures query normalization (`Locale.ROOT` lowercasing, Unicode regex splitting `[^\p{L}\p{N}]+`, and deduplication). It takes ~2 microseconds, proving that text normalization adds virtually zero CPU overhead.
- **`keywordSearch`:** Measures a multi-term query, for example, (`"adventure island"`) without metadata filters, retrieving all 500 matching candidate books from SQLite, computing term-frequency and title-hit relevance scores for every candidate, sorting all 500 books, and paginating the top 20.
- **`filteredSearch`:** Measures the same query combined with `author` and `language` filters (`author="Author 4"`, `language="en"`). It is **more than 2x faster** than `keywordSearch` because `SearchEngine` prunes non-matching books *before* executing the expensive relevance sorting and title tokenization step.

---

## 2. Concurrent HTTP Thread Scaling (`concurrency-results.csv`)

This benchmark starts a real **Javalin/Jetty HTTP server** and sends concurrent `GET /search?q=adventure+island&language=en&offset=0&limit=20` requests using a pool of **1, 2, 4, and 8 simultaneous client threads**. It records the average response time (`Avg Latency`), tail latency (`p95 Latency`, meaning 95% of requests finished faster than this value), and total server capacity (`Throughput` in requests/second).

| Client Threads | Total Requests | Avg Latency (ms) | p95 Latency (ms) | Throughput (req/s) |
|----------------|----------------|------------------|------------------|--------------------|
| 1 | 40 | 14.905 | 20.046 | 66.49 |
| 2 | 80 | 8.731 | 14.734 | 217.15 |
| 4 | 160 | 8.736 | 16.586 | 431.20 |
| 8 | 320 | 10.880 | 27.658 | 647.74 |

### What this table shows:
- **Near-linear throughput scaling:** When increasing concurrency from `1` to `8` threads, total server throughput grows nearly **10x** (from `66.49 req/s` to `647.74 req/s`).
- **Stable response latency under load:** Average latency stays low (`8.73 ms` at 2–4 threads and `10.88 ms` at 8 threads), and `p95` tail latency remains below `28 ms` even at maximum concurrency.
- These numbers confirm that keeping the inverted index in a lock-free `IndexSnapshot` in RAM and opening SQLite connections with `PRAGMA query_only = ON` allows worker threads to serve queries in parallel without blocking one another.

---

## 3. Generated Plots Description

- **`jmh-latency.png` (Component Latency Bar Chart):**
  Displays the average execution time (`ms/op`) and error bars (`± 99.9% CI`) for `filteredSearch`, `keywordSearch`, and `tokenizeQuery`. Visually highlights that SQLite candidate retrieval + sorting dominates search time, and that early metadata filtering cuts query cost in half.
- **`concurrency-scaling.png` (HTTP Throughput & Latency Scaling Curves):**
    - **Left panel (*Search Throughput vs. Concurrency*):** Shows the upward curve of processed `requests/sec` as concurrent threads increase from 1 to 8, demonstrating effective multi-core utilization without lock contention.
    - **Right panel (*Search Query Latency Avg & p95*):** Compares the green average latency line against the red dashed `p95` tail-latency line across thread counts, showing that response times remain bounded and predictable under concurrent traffic.