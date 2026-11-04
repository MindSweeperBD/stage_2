
# Mindsweeper — Stage 2

**Big Data · Grado en Ciencia e Ingeniería de Datos · Universidad de Las Palmas de Gran Canaria**

Mindsweeper is a Java 17 service-oriented search engine for Project Gutenberg books. Stage 2 runs **four separate processes on one machine**: Ingestion (port 7001), Indexing (7002), Search (7003), and a Control Module (7004). The services communicate over HTTP and share filesystem-based data layers. Stage 3 will address multi-host deployment.

## 1. Prerequisites and repository layout

Install **JDK 17+**, **Apache Maven**, and **curl**. Bash is needed for the provided API-check and indexing benchmark scripts; Python 3 is needed for plotting and storage reports. Internet access is required to download books from Project Gutenberg and to resolve Maven dependencies on first build.

```text
stage_2-main/
├── README.md
├── ingestion-service/    # Java/Javalin; api/openapi.yaml; scripts/test-api.sh
├── indexing-service/     # Java/Javalin; api/openapi.yaml; scripts/validate_api.sh
├── search-service/       # Java/Javalin; api/openapi.yaml; scripts/test-api.sh
└── control-module/       # Java/Javalin orchestration and persistent pipeline state
```

Run all commands below from the **repository root** unless a `cd` command is shown. Each microservice is a separate Maven project; there is no root aggregator `pom.xml`.

## 2. Build all four components with Maven

```bash
(cd ingestion-service && mvn clean package)
(cd indexing-service && mvn clean package)
(cd search-service && mvn clean package)
(cd control-module && mvn clean package)
```

Maven runs the JUnit tests during `package`. To run tests independently:

```bash
for service in ingestion-service indexing-service search-service control-module; do
  (cd "$service" && mvn test) || exit 1
done
```

Expected executable shaded JAR paths, as configured by the Maven POMs:

| Component | Executable |
|---|---|
| Ingestion | `ingestion-service/target/ingestion-service.jar` |
| Indexing | `indexing-service/target/indexing-service-1.0.0.jar` |
| Search | `search-service/target/search-service.jar` |
| Control | `control-module/target/control-module.jar` |

If a build fails, inspect its Maven output; the exact build and runtime behavior has not been independently executed as part of this README review.

## 3. Configure and run on a single machine

**Important:** The default relative paths are resolved against each process's **working directory**. To avoid creating separate datalakes/datamarts, configure **absolute paths shared by all processes**. The example below uses the repository root as the working directory and sets absolute paths explicitly.

Open a terminal in the repository root, then create a shared configuration file:

```bash
export PROJECT_ROOT="$(pwd)"
mkdir -p "$PROJECT_ROOT/runtime/datalake" "$PROJECT_ROOT/runtime/datamart" "$PROJECT_ROOT/runtime/control"
export DATALAKE_PATH="$PROJECT_ROOT/runtime/datalake"
export DATAMART_PATH="$PROJECT_ROOT/runtime/datamart"
export METADATA_DB_PATH="$DATAMART_PATH/metadata.db"
export INVERTED_INDEX_PATH="$DATAMART_PATH/inverted_index.json"
export CONTROL_STATE_PATH="$PROJECT_ROOT/runtime/control/pipeline-state.json"
export INGESTION_PORT=7001 INDEXING_PORT=7002 SEARCH_PORT=7003 CONTROL_PORT=7004
export INGESTION_URL=http://localhost:7001
export INDEXING_URL=http://localhost:7002
export SEARCH_URL=http://localhost:7003
```

**Each new terminal must inherit the same exported variables.** For example, save the block to a local shell file and `source` it in every terminal. Do not commit machine-specific absolute paths.

Start each service in a **separate terminal**, from the repository root, in this order:

```bash
# Terminal 1 — Ingestion
java -jar ingestion-service/target/ingestion-service.jar

# Terminal 2 — Indexing
java -jar indexing-service/target/indexing-service-1.0.0.jar

# Terminal 3 — Search
java -jar search-service/target/search-service.jar

# Terminal 4 — Control Module
java -jar control-module/target/control-module.jar
```

Check all four processes:

```bash
for port in 7001 7002 7003 7004; do
  echo "--- localhost:$port ---"
  curl --fail-with-body -sS "http://localhost:$port/status"; echo
done
```

**Coordination:** `POST /process/{book_id}` on Control invokes Ingestion, checks ingestion completion, calls Indexing, and verifies publication through Search `GET /books/{book_id}`. Per-book `ReentrantLock` instances serialize requests for the same ID **within one Control process**. Pipeline states are persisted in the JSON file specified by `CONTROL_STATE_PATH`. This does not provide cross-process distributed locking.

## 4. Configuration reference

| Variable | Component(s) | Default | Purpose |
|---|---|---|---|
| `INGESTION_PORT` | Ingestion | `7001` | Ingestion HTTP listener |
| `INDEXING_PORT` | Indexing | `7002` | Indexing HTTP listener |
| `SEARCH_PORT` | Search | `7003` | Search HTTP listener |
| `CONTROL_PORT` | Control | `7004` | Control HTTP listener |
| `DATALAKE_PATH` | Ingestion, Indexing | `book_datalake` | Shared book directory |
| `DATAMART_PATH` | Indexing, Search | `book_datamart` | Shared indexed data directory |
| `METADATA_DB_PATH` | Search | `<DATAMART_PATH>/metadata.db` | SQLite metadata database |
| `INVERTED_INDEX_PATH` | Search | `<DATAMART_PATH>/inverted_index.json` | Inverted index file |
| `CONTROL_STATE_PATH` | Control | `data/pipeline-state.json` | Persisted pipeline states |
| `INGESTION_URL` | Control | `http://localhost:7001` | Upstream ingestion endpoint |
| `INDEXING_URL` | Control | `http://localhost:7002` | Upstream indexing endpoint |
| `SEARCH_URL` | Control | `http://localhost:7003` | Search verification endpoint |
| `GUTENBERG_BASE_URL` | Ingestion | `https://www.gutenberg.org/cache/epub/` | Book source base URL |

**Replica counts:** The application does not expose a `REPLICAS` setting or a replica launcher. One instance of each component is the documented baseline. Additional processes would require different listening ports and careful handling of shared files and Control state; they are **not** validated by this README.

**Thread settings:** Javalin's server thread pools are not exposed as an application environment-variable setting in these `App` classes. The repository does provide `JMH_THREADS_LIST` for **indexing benchmark threads**, not for production HTTP worker threads. The benchmark script defaults to `1 2 4`. Do not interpret benchmark thread counts as process replica counts.

## 5. End-to-end use cases and test queries

Once all services are healthy, submit a Gutenberg book to the orchestrator:

```bash
curl --fail-with-body -sS -X POST http://localhost:7004/process/1342
curl --fail-with-body -sS http://localhost:7004/state/1342
curl --fail-with-body -sS http://localhost:7001/ingest/status/1342
curl --fail-with-body -sS http://localhost:7002/index/status
curl --fail-with-body -sS http://localhost:7003/books/1342
```

The `POST /process/1342` request runs the workflow synchronously and may take time or fail if Gutenberg is unavailable. A successfully verified book should be reported as `VERIFIED` by the Control Module.

Try Search's public-facing API:

```bash
curl --get --fail-with-body -sS http://localhost:7003/search --data-urlencode 'q=adventure'
curl --get --fail-with-body -sS http://localhost:7003/search \
  --data-urlencode 'q=adventure' --data-urlencode 'language=en' \
  --data-urlencode 'offset=0' --data-urlencode 'limit=5'
curl --fail-with-body -sS http://localhost:7003/search/filters
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:7003/search
```

Query results depend on which books have been ingested and indexed. The last command checks the HTTP response for a missing query parameter; consult the Search OpenAPI specification for the intended contract.

### API boundaries and contracts

| Provider | Contract location | Main interfaces | Consumer |
|---|---|---|---|
| Ingestion | [`ingestion-service/api/openapi.yaml`](ingestion-service/api/openapi.yaml) | `POST /ingest/{book_id}`, `GET /ingest/status/{book_id}`, `GET /ingest/list`, `GET /ingest/stats`, `GET /status` | Control, operators |
| Indexing | [`indexing-service/api/openapi.yaml`](indexing-service/api/openapi.yaml) | `POST /index/update/{book_id}`, `POST /index/rebuild`, `GET /index/status`, `GET /status` | Control, operators |
| Search | [`search-service/api/openapi.yaml`](search-service/api/openapi.yaml) | `GET /search`, `GET /books/{book_id}`, `GET /search/filters`, `GET /status` | HTTP clients, Control |
| Control | Java implementation: `control-module/src/main/java/com/mindsweeper/control/ControlApp.java` | `POST /process/{book_id}`, `GET /state/{book_id}`, `GET /status` | Operators / integration clients |

The YAML files contain the complete HTTP contract details for the three services. **No Control OpenAPI YAML file was found in the supplied ZIP**; its endpoints are documented here from `ControlApp.java`. Non-HTTP contracts include the shared datalake completion marker `.complete`, the datamart files, and Control's JSON state file. All four processes run on the same Stage 2 host.

## 6. Interface validation and unit tests

Run the supplied HTTP smoke-check scripts **after starting the services**:

```bash
bash ingestion-service/scripts/test-api.sh http://localhost:7001
INDEXING_BASE_URL=http://localhost:7002 BOOK_ID=1342 bash indexing-service/scripts/validate_api.sh
bash search-service/scripts/test-api.sh http://localhost:7003
```

The Indexing check assumes book 1342 has already been ingested. Its script **modifies the index** and runs a full rebuild. The Ingestion script performs a real Gutenberg download and therefore needs internet access. Search's script checks normal and invalid queries; its output depends on the available index. Control can be smoke-tested with the `/status`, `/process/{book_id}`, and `/state/{book_id}` commands in Section 5; no separate Control validation shell script is present.

Run Java tests from each service directory with `mvn test`. Unit/API tests are located under each module's `src/test/java/` directory. These checks exercise behavior; the existence of scripts does not, by itself, prove they pass in every environment.

## 7. Benchmarks and results

Benchmark source files are under each service's `src/test/java/` directory; output directories are `*/benchmarks/results/`.

**Indexing:** The repository provides a complete shell entry point. From the root:

```bash
(cd indexing-service && JMH_THREADS_LIST='1 2 4' bash scripts/run_benchmarks.sh)
```

The script compiles tests, builds the test classpath, invokes JMH at 1, 2 and 4 benchmark threads, writes `jmh-indexing-t1.csv`, `jmh-indexing-t2.csv`, and `jmh-indexing-t4.csv`, and calls `scripts/plot_results.py`. **Note:** this script calls `"${JAVA_HOME}/bin/java"`; set `JAVA_HOME` to your JDK installation before running it. Python plotting dependencies must also be installed.

**Ingestion and Search:** Each has a JMH `BenchmarkRunner` under `src/test/java/.../benchmark/`, but there is **no equivalent benchmark shell script** in the supplied repository. The following Maven commands build the test classpath and invoke the JMH main class directly (run each from its service directory):

```bash
mvn test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  org.openjdk.jmh.Main '.*IngestionBenchmark.*' \
  -wi 5 -i 10 -f 1 -t 1 -rf csv -rff benchmarks/results/jmh-results.csv
```

For Search, substitute `SearchBenchmark` for `IngestionBenchmark` and run in `search-service/`. These classpath commands target Unix-like systems; Windows classpath separators differ. They are **documented invocation instructions, not a claim that the benchmarks were run here**. JMH can be slow because it performs warmup, measurement, and forks.

Existing result files can be found at:

- `ingestion-service/benchmarks/results/` (`jmh-results.csv`, `storage.csv`)
- `indexing-service/benchmarks/results/` (JMH thread comparisons, summary CSV, plots)
- `search-service/benchmarks/results/` (latency and concurrency CSV/plots)

**Measurement provenance:** Do not assume that a CSV named `jmh-results.csv` was generated by JMH. Verify its producer, configuration, units, and raw output before reporting it as a JMH measurement. The Ingestion CSV has previously been associated with local timing measurements rather than verified JMH execution.

The Ingestion storage-report helper is `ingestion-service/scripts/storage-report.py`; check its CLI usage before running it. Search plotting code is in `search-service/scripts/plot-benchmarks.py`. These scripts are separate from the JMH benchmark runner classes.

## 8. Concurrency, storage and known limitations

- Ingestion writes `header.txt` and `body.txt` into `DATALAKE_PATH/<book_id>/`; the `.complete` marker indicates that a book is ready for Indexing. Per-book locking protects concurrent requests **inside one Ingestion process**.
- Indexing reads completed books and publishes metadata/inverted-index data in the datamart. Search consumes those files; its behavior depends on the datamart being present and up to date.
- Control stores pipeline states in `CONTROL_STATE_PATH` and serializes duplicate requests per book **within one Control process**. A failed request is marked `FAILED`; calling the process endpoint again can retry the workflow, but there is no demonstrated background retry scheduler.
- A single-machine filesystem is a Stage 2 assumption. Multiple replicas sharing files require additional synchronization and consistency work; they should not be launched as though replica safety were already guaranteed.
- The provided repository does not demonstrate a production thread-pool configuration interface, a multi-host deployment, or a comprehensive automated Control API contract suite.

## 9. Stage 3 directions

Stage 3 should replace local-only assumptions with explicit cross-machine communication and shared/distributed storage, task ownership, failure recovery, safe publication of new index versions, service discovery, and repeatable integration/load tests. Measure coordination overhead and resource utilization under controlled workloads before drawing conclusions about replica scaling.
