#!/usr/bin/env python3
"""Compare Control's on-demand and periodic-batch HTTP workflows.

Use distinct, not-yet-verified book IDs for each strategy so idempotency does
not turn the measurements into no-op requests. Requires all four services.
"""
import argparse
import csv
import json
import time
import urllib.error
import urllib.request
from pathlib import Path
import time


def request_json(url, method="GET", timeout=180):
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} from {url}: {body}") from exc


def parse_ids(raw):
    ids = [int(part.strip()) for part in raw.split(",") if part.strip()]
    if not ids or any(book_id <= 0 for book_id in ids):
        raise argparse.ArgumentTypeError("Provide comma-separated positive book IDs")
    if len(ids) != len(set(ids)):
        raise argparse.ArgumentTypeError("Book IDs must not repeat within a strategy")
    return ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:7004")
    parser.add_argument("--demand-ids", required=True, type=parse_ids,
                        help="Comma-separated IDs for the on-demand run")
    parser.add_argument("--periodic-ids", required=True, type=parse_ids,
                        help="Different IDs for the periodic-batch run")
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    parser.add_argument("--output", default=f"control-module/benchmarks/results/comparison_{timestamp}.csv")
    args = parser.parse_args()

    if len(args.demand_ids) != len(args.periodic_ids):
        parser.error("Use the same number of IDs for both strategies")
    if set(args.demand_ids) & set(args.periodic_ids):
        parser.error("Use different IDs for each strategy to avoid idempotent no-op measurements")

    base = args.base_url.rstrip("/")
    rows = []

    # Strategy A: each book is requested separately.
    demand_start = time.perf_counter()
    demand_per_book = []
    for book_id in args.demand_ids:
        start = time.perf_counter()
        try:
            _, result = request_json(f"{base}/process/{book_id}", method="POST")
            state = result.get("state", "unknown")
        except Exception as e:
            print(f"[WARNING] Book {book_id} failed: {e}")
            result = {"state": "FAILED"}
            state = "FAILED"

        elapsed_ms = (time.perf_counter() - start) * 1000
        demand_per_book.append(elapsed_ms)
        rows.append({"strategy": "on_demand", "book_id": book_id,
                     "elapsed_ms": round(elapsed_ms, 3),
                     "state": state})
    demand_total_ms = (time.perf_counter() - demand_start) * 1000

    # Strategy B: enqueue the batch and trigger the same batch worker used by the scheduler.
    for book_id in args.periodic_ids:
        _, queued = request_json(f"{base}/periodic/queue/{book_id}", method="POST")
        if not queued.get("queued"):
            raise RuntimeError(f"Book {book_id} was not queued (state={queued.get('state')})")
    _, batch = request_json(f"{base}/periodic/run", method="POST", timeout=600)
    periodic_total_ms = float(batch.get("duration_ms", 0))
    results = {item.get("book_id"): item for item in batch.get("results", [])}
    for book_id in args.periodic_ids:
        item = results.get(book_id, {})
        rows.append({"strategy": "periodic_batch", "book_id": book_id,
                     "elapsed_ms": "", "state": item.get("state", "not_returned")})

    count = len(args.demand_ids)  # <-- Definir la variable aquí arriba

    demand_rate = count / (demand_total_ms / 1000) if demand_total_ms > 0 else 0
    periodic_rate = count / (periodic_total_ms / 1000) if periodic_total_ms > 0 else 0

    rows.extend([
        {"strategy": "on_demand_total", "book_id": "ALL",
         "elapsed_ms": round(demand_total_ms, 3),
         "state": f"throughput_books_per_second={demand_rate:.3f}"},
        {"strategy": "periodic_batch_total", "book_id": "ALL",
         "elapsed_ms": round(periodic_total_ms, 3),
         "state": f"processed={batch.get('processed')};failed={batch.get('failed')};throughput_books_per_second={periodic_rate:.3f}"},
    ])

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["strategy", "book_id", "elapsed_ms", "state"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"Books per strategy: {count}")
    print(f"On-demand total: {demand_total_ms:.3f} ms; throughput: {count / (demand_total_ms / 1000):.3f} books/s")
    print(f"Periodic batch execution: {periodic_total_ms:.3f} ms; throughput: {count / (periodic_total_ms / 1000):.3f} books/s" if periodic_total_ms > 0 else "Periodic batch duration unavailable")
    print(f"Periodic batch reports processed={batch.get('processed')}, failed={batch.get('failed')}")
    print(f"CSV written to: {output.resolve()}")
    print("Note: periodic_total_ms measures batch execution, not the scheduler's up-to-interval waiting delay.")


if __name__ == "__main__":
    main()
