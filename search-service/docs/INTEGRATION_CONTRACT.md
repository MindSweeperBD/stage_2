# Search Service and Datamart Integration Contract

**Version:** 1.1.0  
**Project:** Mindsweeper Stage 2  
**Provider:** Search Service (`com.mindsweeper.search`, default port `7003`)  
**Upstream Producer:** Indexing Service (`com.mindsweeper.indexing`, default port `7002`)  
**Intended Consumers:** Search Front-End, HTTP validation clients, and Control Module (`com.mindsweeper.control`, default port `7004`)

---

## 1. Datamart Storage Contract

The Search Service operates strictly in **read-only mode** over the datamarts generated and maintained by the Indexing Service:

```text
book_datamart/
    metadata.db
    inverted_index.json
```

Configurable environment variables:
- `SEARCH_PORT`: HTTP listening port (default: `7003`).
- `DATAMART_PATH`: Base directory for datamarts (default: `book_datamart`).
- `METADATA_DB_PATH`: Path to the SQLite metadata database (default: `<DATAMART_PATH>/metadata.db`).
- `INVERTED_INDEX_PATH`: Path to the monolithic JSON inverted index (default: `<DATAMART_PATH>/inverted_index.json`).

### 1.1 Metadata Datamart (`metadata.db`)
- **Engine:** SQLite 3 (`books` table).
- **Expected Schema:**
  - `book_id` (`INTEGER PRIMARY KEY`)
  - `title` (`TEXT NOT NULL`)
  - `author` (`TEXT NOT NULL`)
  - `language` (`TEXT NOT NULL`)
  - `year` (`INTEGER`, optional/nullable for backward compatibility with Stage 1 databases)
  - `body_path` (`TEXT NOT NULL`)
- **Read Safety:** Connections are opened with `PRAGMA query_only = ON;` and `PRAGMA busy_timeout = 3000;` so search queries never modify metadata and gracefully wait if the Indexing Service is committing a transaction.

### 1.2 Inverted Index Datamart (`inverted_index.json`)
- **Format:** UTF-8 JSON object mapping normalized lowercase terms (`String`) to sorted lists of `book_id` integers (`List<Integer>`).
- **Tokenization Rules (aligned with `com.mindsweeper.indexing.TextNormalizer`):**
  1. Convert text to lowercase (`Locale.ROOT`).
  2. Replace any non-Unicode letter or digit sequence with whitespace.
  3. Split by whitespace and discard single-character tokens (`length > 1`).

---

## 2. Index Consistency and Publication Visibility

Searches must only observe complete, consistent index states:

1. **Atomic Publication by Indexing Service:** The Indexing Service writes updates to a temporary file (`inverted-index-*.tmp`) inside `book_datamart/` and atomically replaces `inverted_index.json` (`ATOMIC_MOVE`, `REPLACE_EXISTING`) inside a transactional publication flow.
2. **Lock-Free In-Memory Snapshot in Search Service:** `DatamartStore` caches an immutable `IndexSnapshot` storing the file's `lastModifiedMillis`, `fileSize`, and unmodifiable posting sets.
3. **Automatic Refresh:** On each query, `DatamartStore` checks the file metadata of `inverted_index.json`. If unchanged, threads read the in-memory map with zero lock contention. If updated, a synchronized reload swaps atomically.
4. **Startup Before Indexing:** If `metadata.db` or `inverted_index.json` does not exist yet, the service starts cleanly and returns empty search results (`200 OK` with `"total": 0, "results": []`) and `"metadata_db_available": false` in `/status`.

---

## 3. Front-End-Facing and Control Module API Semantics

### 3.1 Main Search Endpoint
`GET /search?q={term}&author={name}&language={code}&year={YYYY}&offset={offset}&limit={limit}`

- **Required Parameter:**
  - `q`: Keyword query string (not null). Multi-word queries are tokenized into unique normalized terms.
- **Filter Matching Rules:**
  - `author`: Case-insensitive substring match (e.g., `author=Austen` matches `"Jane Austen"`).
  - `language`: Case-insensitive normalization. Both ISO codes (`en`, `es`, `fr`, `de`, `it`, `pt`, `nl`, `la`) and full Gutenberg names extracted by Indexing Service are normalized to 2-letter ISO codes so `?language=en` matches books stored as `"English"` or `"en"`.
  - `year`: Exact integer match against publication year (`year > 0`).
- **Result Ordering (Ranking Criteria):**
  1. **Term Match Count (Descending):** Books matching more distinct query terms rank higher.
  2. **Title Hit Count (Descending):** Ties are broken by how many query terms appear in the book's `title`.
  3. **Book ID (Ascending):** Final deterministic tie-breaker (`book_id`) to ensure stable pagination across requests.
- **Pagination Defaults and Limits:**
  - `offset`: Default `0`, minimum `0`.
  - `limit`: Default `20`, minimum `1`, maximum `100`.
  - `total` reports the total number of matching books **before** pagination; `results` contains only the requested slice.
- **Status Codes and Error Semantics:**
  - `200 OK`: Valid query (including when `total = 0`, returning `"results": []`).
  - `400 Bad Request`: Missing/blank `q`, negative `offset`, `limit` outside `1..100`, or non-integer/non-positive `year`.

### 3.2 Control Module Verification and Operational Endpoints
- `GET /books/{book_id}`: Used by both front-end clients and the **Control Module** (`ServiceClient.isBookIndexed`) after `POST /index/update/{book_id}` to verify that the newly indexed book is visible in the Search Service before transitioning the pipeline state to `VERIFIED`. Returns `200 OK` with book metadata, `404 Not Found` if not indexed, or `400 Bad Request` if `book_id` is not a positive integer.
- `GET /search/filters`: Returns sorted distinct lists of available `authors`, normalized `languages`, and `years` (`200 OK`).
- `GET /status`: Operational health check returning service status, `indexed_books`, `indexed_terms`, and datamart availability flags (`200 OK`).

---

## 4. Concurrency, Threading, and Replication

- **In-Process Multithreading:** A single `search-service` process uses the Javalin/Jetty worker thread pool to serve dozens of concurrent HTTP requests simultaneously. Because the in-memory inverted index (`IndexSnapshot`) is immutable (never modified in place, only atomically replaced as a whole via a `volatile` reference when the file changes) and SQLite connections are opened in read-only mode (`PRAGMA query_only = ON`), worker threads do not block one another (lock-free reads).
- **Process Replication:** Replication consists of launching two or more independent `search-service` Java processes on different ports (e.g., `SEARCH_PORT=7003` and `SEARCH_PORT=7013`). Unlike `ingestion-service` and `indexing-service`,`search-service` is **read-only**. Consequently, `search-service` can immediately run multiple concurrent replicas pointing to the same `book_datamart/` directory with zero risk of write conflicts or data corruption.

---

## 5. Stage 3 Cluster Evolution

To transition from a single-machine deployment in Stage 2 to a multi-node cluster in Stage 3:
1. **Cross-Node Datamart Access:** Local filesystem paths (`book_datamart/metadata.db` and `book_datamart/inverted_index.json`) must be replaced either by snapshot replication (distributing published datamart versions to each Search Service node) or by a networked storage backend (e.g., MongoDB/PostgreSQL behind `DatamartStore`).
2. **Load Balancing:** A reverse proxy or API gateway will distribute incoming front-end `GET /search` traffic across Search Service replicas running on separate cluster nodes.
3. **Decoupled Architecture:** Because `App` (REST controller) and `SearchEngine` (ranking/filtering logic) are decoupled from `DatamartStore` (data access), only `DatamartStore` requires adaptation for Stage 3.