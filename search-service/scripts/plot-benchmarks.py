#!/usr/bin/env python3

import csv
import pathlib
import platform

try:
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False


def parse_float(value: str) -> float:
    return float(value.strip().replace(",", "."))


def load_jmh_results(csv_path: pathlib.Path):
    rows = []
    if not csv_path.is_file():
        return rows

    with csv_path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            short_name = row["Benchmark"].split(".")[-1]
            score_err_key = next(
                (k for k in row.keys() if "Score Error" in k),
                None
            )
            rows.append({
                "benchmark": short_name,
                "mode": row["Mode"],
                "threads": int(row["Threads"]),
                "samples": int(row["Samples"]),
                "score": parse_float(row["Score"]),
                "error": parse_float(row[score_err_key]) if score_err_key else 0.0,
                "unit": row["Unit"]
            })
    return rows


def load_concurrency_results(csv_path: pathlib.Path):
    rows = []
    if not csv_path.is_file():
        return rows

    with csv_path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append({
                "threads": int(row["threads"]),
                "total_requests": int(row["total_requests"]),
                "avg_latency_ms": parse_float(row["avg_latency_ms"]),
                "p95_latency_ms": parse_float(row["p95_latency_ms"]),
                "throughput": parse_float(row["throughput_req_per_sec"])
            })
    return rows


def write_results_readme(results_dir: pathlib.Path, jmh_rows, conc_rows):
    readme_path = results_dir / "README.md"
    lines = [
        "# Search Service — Benchmark Results",
        "",
        "## Host Environment",
        f"- **System:** `{platform.platform()}`",
        f"- **Processor:** `{platform.processor() or platform.machine()}`",
        f"- **Python Version:** `{platform.python_version()}`",
        "",
        "## 1. JMH Component Microbenchmarks (`jmh-results.csv`)",
        "",
        "| Operation | Mode | Threads | Samples | Score (ms/op) | Error ± (99.9%) |",
        "|-----------|------|---------|---------|---------------|-----------------|"
    ]

    for r in jmh_rows:
        lines.append(
            f"| `{r['benchmark']}` | `{r['mode']}` | {r['threads']} | "
            f"{r['samples']} | {r['score']:.4f} | ± {r['error']:.4f} |"
        )

    lines.extend([
        "",
        "## 2. Concurrent HTTP Thread Scaling (`concurrency-results.csv`)",
        "",
        "| Client Threads | Total Requests | Avg Latency (ms) | p95 Latency (ms) | Throughput (req/s) |",
        "|----------------|----------------|------------------|------------------|--------------------|"
    ])

    for r in conc_rows:
        lines.append(
            f"| {r['threads']} | {r['total_requests']} | "
            f"{r['avg_latency_ms']:.3f} | {r['p95_latency_ms']:.3f} | {r['throughput']:.2f} |"
        )

    readme_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[OK] Generated report: {readme_path}")


def generate_plots(results_dir: pathlib.Path, jmh_rows, conc_rows):
    if not HAS_MATPLOTLIB:
        print("[INFO] Install matplotlib (`pip install matplotlib`) to generate PNG charts.")
        return

    if jmh_rows:
        names = [r["benchmark"] for r in jmh_rows]
        scores = [r["score"] for r in jmh_rows]
        errors = [r["error"] for r in jmh_rows]

        fig, ax = plt.subplots(figsize=(7, 4.5))
        bars = ax.bar(names, scores, yerr=errors, capsize=5, color=["#2563eb", "#059669", "#d97706"])
        ax.set_ylabel("Average Time (ms/op)")
        ax.set_title("Search Service — JMH Microbenchmark Latency")
        ax.grid(axis="y", linestyle="--", alpha=0.5)

        for bar, score in zip(bars, scores):
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                bar.get_height() + max(scores) * 0.03,
                f"{score:.4f} ms",
                ha="center",
                va="bottom",
                fontsize=9
            )

        fig.tight_layout()
        jmh_plot = results_dir / "jmh-latency.png"
        fig.savefig(jmh_plot, dpi=200)
        plt.close(fig)
        print(f"[OK] Saved chart: {jmh_plot}")

    if conc_rows:
        threads = [r["threads"] for r in conc_rows]
        throughput = [r["throughput"] for r in conc_rows]
        avg_lat = [r["avg_latency_ms"] for r in conc_rows]
        p95_lat = [r["p95_latency_ms"] for r in conc_rows]

        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.5))

        ax1.plot(threads, throughput, marker="o", linewidth=2, color="#2563eb", label="Throughput (req/s)")
        ax1.set_xticks(threads)
        ax1.set_xlabel("Concurrent Threads")
        ax1.set_ylabel("Throughput (requests / sec)")
        ax1.set_title("Search Throughput vs. Concurrency")
        ax1.grid(True, linestyle="--", alpha=0.5)
        ax1.legend()

        ax2.plot(threads, avg_lat, marker="s", linewidth=2, color="#059669", label="Avg Latency (ms)")
        ax2.plot(threads, p95_lat, marker="^", linewidth=2, linestyle="--", color="#dc2626", label="p95 Latency (ms)")
        ax2.set_xticks(threads)
        ax2.set_xlabel("Concurrent Threads")
        ax2.set_ylabel("Response Time (ms)")
        ax2.set_title("Search Query Latency (Avg & p95)")
        ax2.grid(True, linestyle="--", alpha=0.5)
        ax2.legend()

        fig.tight_layout()
        conc_plot = results_dir / "concurrency-scaling.png"
        fig.savefig(conc_plot, dpi=200)
        plt.close(fig)
        print(f"[OK] Saved chart: {conc_plot}")


def main():
    script_dir = pathlib.Path(__file__).resolve().parent
    results_dir = script_dir.parent / "benchmarks" / "results"
    results_dir.mkdir(parents=True, exist_ok=True)

    jmh_csv = results_dir / "jmh-results.csv"
    conc_csv = results_dir / "concurrency-results.csv"

    jmh_rows = load_jmh_results(jmh_csv)
    conc_rows = load_concurrency_results(conc_csv)

    write_results_readme(results_dir, jmh_rows, conc_rows)
    generate_plots(results_dir, jmh_rows, conc_rows)


if __name__ == "__main__":
    main()
