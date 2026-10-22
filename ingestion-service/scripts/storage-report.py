#!/usr/bin/env python3

import argparse
import csv
import pathlib
import platform

parser = argparse.ArgumentParser()

parser.add_argument("datalake")

parser.add_argument(
    "--output",
    default="benchmarks/results/storage.csv"
)

args = parser.parse_args()

root = pathlib.Path(args.datalake)

books = []
files = []
directories = []

if root.exists():

    for path in root.iterdir():

        if not path.is_dir():
            continue

        if (
            (path / ".complete").is_file()
            and (path / "header.txt").is_file()
            and (path / "body.txt").is_file()
            and (path / "body.txt").stat().st_size > 0
        ):
            books.append(path)

    files = [
        path
        for path in root.rglob("*")
        if path.is_file()
    ]

    directories = [
        path
        for path in root.rglob("*")
        if path.is_dir()
    ]

total_bytes = sum(
    path.stat().st_size
    for path in files
)

directory_count = len(directories)

if root.is_dir():
    directory_count += 1

row = {
    "root": str(root.resolve()),
    "complete_books": len(books),
    "files": len(files),
    "directories": directory_count,
    "bytes": total_bytes,
    "python": platform.python_version(),
    "system": platform.platform()
}

output = pathlib.Path(args.output)

output.parent.mkdir(
    parents=True,
    exist_ok=True
)

with output.open(
    "w",
    newline="",
    encoding="utf-8"
) as file:

    writer = csv.DictWriter(
        file,
        fieldnames=row.keys()
    )

    writer.writeheader()
    writer.writerow(row)

print("Storage report:")
print(row)
