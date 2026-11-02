# Integration Contract – Indexing Service

**Contract version:** 1.1.0  
**Provider:** Indexing Service (`com.mindsweeper.indexing`)  
**Internal caller:** Control Module / workflow coordinator  
**Downstream consumer:** Search Service  
**Default address:** `http://localhost:7002`

## 1. Ingestion-to-indexing input contract

The Indexing Service reads only the Ingestion Service datalake rooted at `DATALAKE_PATH` (default `book_datalake`).

```text
<DATALAKE_PATH>/
  <book_id>/
    header.txt
    body.txt
    .complete
```

A book is ready only when `.complete`, `header.txt` and `body.txt` are regular files and `body.txt` is not blank. The reader captures file attributes before and after reading to detect replacement during the read. The Ingestion Service must create `.complete` only after both text files have been written.

## 2. HTTP interface inventory

These endpoints are internal/operational. The front-end-facing search API belongs to Search Service.

| Method and path | Provider | Consumer | Meaning |
|---|---|---|---|
| `POST /index/update/{book_id}` | Indexing Service | Control Module | Index or refresh one ready book. |
| `POST /index/rebuild` | Indexing Service | Control Module / operator | Rebuild both datamarts from all ready books. |
| `GET /index/status` | Indexing Service | Control Module / operator | Read counts, version and publication status. |
| `GET /status` | Indexing Service | Operator / health check | Basic process health. |

The exact HTTP schema is in `api/openapi.yaml`.

### `POST /index/update/{book_id}`

No request body. `book_id` must be a positive integer. The operation is synchronous; `200` means the metadata row and new indexes have been published.

```json
{
  "book_id": 1342,
  "index": "updated",
  "version": "<sha256-of-published-content>",
  "indexed_at": "2026-10-31T15:12:29.755064600Z",
  "unique_terms": 6834
}
```

For unchanged data it returns `index: "already_indexed"`. The comparison checks the metadata record and the set of terms currently associated with that book. Repeated requests do not append duplicate IDs because indexes are sets and `book_id` is a SQLite primary key.

Errors:

- `400 invalid_book_id`: ID is malformed, non-positive or outside the 32-bit integer range.
- `409 book_not_ready`: missing completion marker/files, blank body or files changed while reading.
- `500 indexing_io_error`: SQLite or JSON publication failure. If recovery is required, run a full rebuild before individual updates.

### `POST /index/rebuild`

No request body. Scans only positive numeric directories directly below `DATALAKE_PATH`. Complete books are processed; incomplete directories are skipped and included in `skipped_incomplete`. A missing datalake root or a non-readiness I/O error aborts the rebuild.

```json
{
  "books_processed": 100,
  "skipped_incomplete": 2,
  "elapsed_time_ms": 35200,
  "elapsed_time": "35.200s",
  "version": "<sha256-of-published-content>"
}
```

The operation replaces all rows in `metadata.db` and the entire `inverted_index.json` with the result derived from the datalake.

### `GET /index/status`

```json
{
  "books_indexed": 120,
  "last_update": "2026-10-31T12:00:00Z",
  "index_size_MB": 2.7,
  "index_size_bytes": 2831155,
  "index_version": "<sha256-of-published-content>",
  "unique_terms": 45000,
  "update_in_progress": false
}
```

`index_size_bytes` is the combined on-disk size of `metadata.db` and `inverted_index.json`. `index_size_MB` is calculated as MiB (`bytes / 1,048,576`) to keep the existing API field name. `index_version` is a SHA-256 digest of a deterministic representation of both datamarts; it is not a UUID. On startup, `last_update` is reconstructed from the latest modification time of the two datamart files, so it is an approximate persisted timestamp. If an interrupted publication marker exists at startup, the service reports `index_version: "recovery-required"` and `update_in_progress: true`; its in-memory book/term counts are not considered authoritative until rebuild succeeds.

## 3. Metadata schema

The metadata table follows this structure:

```sql
CREATE TABLE IF NOT EXISTS books (
    book_id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    language TEXT NOT NULL,
    year INTEGER,
    body_path TEXT NOT NULL
);
```

SQLite indexes are created for `author`, `title`, `language` and `year`. Updates use `INSERT OR REPLACE`, and rebuild deletes and reinserts the complete set in a transaction.

## 4. Inverted-index

`inverted_index.json` has this shape:

```json
{
  "adventure": [5, 1342],
  "prejudice": [1342],
  "universally": [1342]
}
```

The key is a normalized word. The value is a sorted list of book IDs in which that word appears. Each ID appears at most once per word; **term frequencies are not stored**.

1. Read only `body.txt` using UTF-8.
2. Convert to lowercase with `Locale.ROOT`.
4. Split on pattern (`[^\\p{L}\\p{N}]+`), which separates the element each time it encounters a character that is not a number or from Unicode.
5. Keep words whose Java `String.length()` is greater than 1.
6. Store distinct terms per book using set semantics.

## 5. Datamart paths and publication consistency

Default datamart output:

```text
<DATAMART_PATH>/
  metadata.db
  inverted_index.json
  .index.lock
  .indexing-in-progress   # exists only during publication or after interrupted publication
```

`metadata.db` and `inverted_index.json` are the two application datamarts. `.index.lock` is an inter-process coordination file; `.indexing-in-progress` is a fail-closed recovery marker.

The writer performs the following under an exclusive OS file lock:

1. Serialize the complete new JSON index into a temporary file in the datamart directory.
2. Back up the previous JSON file when one exists.
3. Create `.indexing-in-progress`.
4. Apply metadata upserts (or a full table replacement for rebuild) inside a SQLite transaction.
5. Atomically replace `inverted_index.json`.
6. Commit the SQLite transaction.
7. Remove `.indexing-in-progress` and release the lock.

If the operation fails before commit, the SQLite transaction is rolled back and the previous JSON is restored. If the process is terminated during the publication window, the marker remains. Readers must refuse to load the pair until a successful `/index/rebuild` repairs it.

### Mandatory Search Service reader protocol

Search Service must implement this protocol whenever it loads or refreshes its metadata/index cache:

1. Open `<DATAMART_PATH>/.index.lock` with read/write access.
2. Acquire a shared `FileLock` (if the operating system does not support shared locks, Java may grant an exclusive lock instead).
3. While holding the lock, check that `.indexing-in-progress` does not exist. If it exists, release the lock and return/retry a temporary-unavailable response; do not load either datamart file.
4. Load `metadata.db` and `inverted_index.json` while holding the same lock.
5. Build one in-memory search view from both, then release the lock. A query must use that one loaded view rather than re-reading one file halfway through the query.

Java reader example (run the database query and JSON load inside the same `try` block):

```java
Path datamart = Path.of("book_datamarts");
DatamartRepository repository = new DatamartRepository(datamart);

try (DatamartRepository.AccessLock lock = repository.acquireReadLock()) {

    Map<Integer, BookMetadata> metadata = repository.loadMetadata();
    Map<String, Set<Integer>> invertedIndex = repository.loadInvertedIndex();

    // Use metadata and invertedIndex here.
}
```

The Indexing Service uses an in-process fair `ReentrantLock` to serialize its own request threads and an OS file lock to coordinate the writer with other processes on the same machine.

If `.indexing-in-progress` remains after a process crash, Control Module/operator must call `POST /index/rebuild`. Individual `POST /index/update/{book_id}` requests are rejected while recovery is pending. A failed rebuild while recovering leaves the marker in place; readers remain blocked until a rebuild completes successfully.

## 6. Concurrency and idempotency

- One fair in-process `ReentrantLock` serializes `updateBook` and `rebuild`.
- The OS file lock protects the two datamart outputs against readers in other local processes.
- `ConcurrentHashMap`/per-book locking is not used here; publication updates a global monolithic index.
- A SQLite primary key and `INSERT OR REPLACE` prevent duplicate metadata rows.
- Inverted index use sets of book IDs, so the same ID is not duplicated for a term.
- Changed book content is processed by removing its old ID from all postings, deleting empty terms, and adding its new distinct words.
- An interrupted publication leaves a marker; Search must fail closed until rebuild.

The lock files coordinate only one host.

## 7. Configuration and caller behavior

| Environment variable | Default | Purpose |
|---|---|---|
| `INDEXING_PORT` | `7002` | HTTP port. |
| `DATALAKE_PATH` | `book_datalake` | Ingestion Service datalake root. |
| `DATAMART_PATH` | `book_datamart` | Directory containing the two datamarts. |

Callers should use a finite HTTP timeout. A timeout does not prove that the operation was cancelled; a safe retry is to query status or repeat the idempotent update request. The caller must not index a book before Ingestion Service confirms it is available.

## 8. Stage 3 considerations

This contract assumes a single host, local paths and OS file locks. On a cluster, storage must become shared or object storage, service addresses must use network discovery, and local file locks must be replaced by a distributed lock/lease, transactional metadata store, broker partition ownership or another documented single-writer mechanism. Single-machine measurements do not establish cluster scalability.
