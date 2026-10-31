#!/usr/bin/env python3
"""Summarize JMH CSV output and generate PNG charts without inventing data."""
from __future__ import annotations

import argparse
import csv
import platform
import os
import re
import statistics
import subprocess
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


def normalized_key(row: dict[str, str], wanted: str) -> str | None:
    wanted = wanted.lower().replace("_", "").replace(" ", "")
    for key in row:
        if key.lower().replace("_", "").replace(" ", "") == wanted:
            return key
    return None


def read_jmh_csv(input_dir: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for csv_path in sorted(input_dir.glob("jmh-indexing-t*.csv")):
        with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            for raw in reader:
                benchmark_key = normalized_key(raw, "Benchmark")
                mode_key = normalized_key(raw, "Mode")
                threads_key = normalized_key(raw, "Threads")
                score_key = normalized_key(raw, "Score")
                unit_key = normalized_key(raw, "Unit")
                if not all((benchmark_key, mode_key, threads_key, score_key, unit_key)):
                    raise ValueError(
                        f"Unexpected JMH CSV columns in {csv_path.name}: {list(raw.keys())}"
                    )
                try:
                    score = float(raw[score_key])  # type: ignore[index]
                    threads = int(raw[threads_key])  # type: ignore[index]
                except (TypeError, ValueError):
                    continue
                benchmark = str(raw[benchmark_key]).split(".")[-1]  # type: ignore[index]
                rows.append({
                    "benchmark": benchmark,
                    "mode": str(raw[mode_key]).strip(),  # type: ignore[index]
                    "threads": threads,
                    "score": score,
                    "unit": str(raw[unit_key]).strip(),  # type: ignore[index]
                    "source_file": csv_path.name,
                })
    return rows


def write_summary(rows: list[dict[str, object]], output: Path) -> None:
    columns = ["benchmark", "mode", "threads", "score", "unit", "source_file"]
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def plot_average_time(rows: list[dict[str, object]], output: Path) -> bool:
    selected = [r for r in rows if str(r["mode"]).lower() in {"avgt", "average time"}
                and str(r["unit"]).lower() in {"ms/op", "us/op", "ns/op", "s/op"}]
    if not selected:
        return False

    grouped: dict[str, list[tuple[int, float]]] = defaultdict(list)
    for row in selected:
        grouped[str(row["benchmark"])].append((int(row["threads"]), float(row["score"])))

    plt.figure(figsize=(9, 5.5))
    for name, values in sorted(grouped.items()):
        values.sort()
        plt.plot([v[0] for v in values], [v[1] for v in values], marker="o", label=name)
    plt.xlabel("JMH worker threads")
    plt.ylabel("Average time per operation (JMH unit)")
    plt.title("Indexing component latency by thread configuration")
    plt.xticks(sorted({int(r["threads"]) for r in selected}))
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=160)
    plt.close()
    return True


def plot_throughput(rows: list[dict[str, object]], output: Path) -> bool:
    selected = [r for r in rows if str(r["mode"]).lower() in {"thrpt", "throughput"}
                and str(r["unit"]).lower() in {"ops/s", "op/s"}]
    if not selected:
        return False
    grouped: dict[str, list[tuple[int, float]]] = defaultdict(list)
    for row in selected:
        grouped[str(row["benchmark"])].append((int(row["threads"]), float(row["score"])))

    plt.figure(figsize=(9, 5.5))
    for name, values in sorted(grouped.items()):
        values.sort()
        plt.plot([v[0] for v in values], [v[1] for v in values], marker="o", label=name)
    plt.xlabel("JMH worker threads")
    plt.ylabel("Throughput (operations/second)")
    plt.title("Indexing component throughput by thread configuration")
    plt.xticks(sorted({int(r["threads"]) for r in selected}))
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=160)
    plt.close()
    return True


def write_results_readme(rows: list[dict[str, object]], output: Path) -> None:
    avgt = [r for r in rows if str(r["mode"]).lower() == "avgt"]
    thrpt = [r for r in rows if str(r["mode"]).lower() == "thrpt"]
    lines = [
        "# Indexing benchmark results",
        "",
        "Generated from the JMH CSV files in this directory by `scripts/plot_results.py`.",
        "These are measurements from the machine on which the script was run; they are not portable performance guarantees.",
        "",
        "## Host information",
        "",
        f"- OS: {platform.platform()}",
        f"- Processor: {platform.processor() or 'not reported by the operating system'}",
        f"- Logical CPUs: {os.cpu_count() or 'unknown'}",
        "- JVM: see the `java -version` output recorded by the person running the benchmark.",
        "",
        "## Average-time results",
        "",
    ]
    if avgt:
        lines.extend([
            "| Benchmark | Threads | Score | Unit |",
            "|---|---:|---:|---|",
        ])
        for r in sorted(avgt, key=lambda x: (str(x["benchmark"]), int(x["threads"]))):
            lines.append(f"| {r['benchmark']} | {r['threads']} | {float(r['score']):.6g} | {r['unit']} |")
    else:
        lines.append("No average-time rows were found in the JMH CSV files.")

    lines.extend(["", "## Throughput results", ""])
    if thrpt:
        lines.extend([
            "| Benchmark | Threads | Score | Unit |",
            "|---|---:|---:|---|",
        ])
        for r in sorted(thrpt, key=lambda x: (str(x["benchmark"]), int(x["threads"]))):
            lines.append(f"| {r['benchmark']} | {r['threads']} | {float(r['score']):.6g} | {r['unit']} |")
    else:
        lines.append("No throughput rows were found in the JMH CSV files.")

    lines.extend([
        "",
        "## Interpretation notes",
        "",
        "- `updateBook` includes reading book_datalake files, Stage 1 metadata extraction, Stage 1 tokenization, posting updates, SQLite metadata persistence, and atomic JSON-index publication.",
        "- `normalizeText` and `extractMetadata` are isolated component microbenchmarks; they do not include HTTP or datamart publication.",
        "- The benchmark compares 1, 2, and 4 JMH worker threads on one host. The service uses a fair in-process writer lock and a local OS file lock, so datamart publications are serialized.",
        "- Threads are not process replicas. These measurements do not demonstrate multi-process or Stage 3 cluster scalability.",
        "- Compare configurations on the same host with the same JVM heap and an otherwise idle machine. Keep the raw JMH CSV files with the report.",
        "",
        "## Plots",
        "",
        "- `average_time.png` (when average-time measurements are present)",
        "- `throughput.png` (when throughput measurements are present)",
        "",
    ])
    output.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path, default=Path("benchmarks/results"))
    args = parser.parse_args()
    input_dir = args.input_dir.resolve()
    rows = read_jmh_csv(input_dir)
    if not rows:
        raise SystemExit(
            f"No JMH CSV rows found in {input_dir}. Run scripts/run_benchmarks.sh first."
        )

    write_summary(rows, input_dir / "indexing_summary.csv")
    plot_average_time(rows, input_dir / "average_time.png")
    plot_throughput(rows, input_dir / "throughput.png")
    write_results_readme(rows, input_dir / "README.md")
    print(f"Processed {len(rows)} JMH result rows in {input_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
