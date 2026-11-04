
# Mindsweeper — Stage 2

**Big Data · Grado en Ciencia e Ingeniería de Datos · Universidad de Las Palmas de Gran Canaria**

Mindsweeper is a Java 17 service-oriented search engine for Project Gutenberg books. Stage 2 runs **four separate processes on one device**: Ingestion (port 7001), Indexing (7002), Search (7003), and a Control Module (7004). The offerings communicate over HTTP and proportion filesystem-primarily based data layers. Stage 3 will address multi-host deployment.

## 1. Prerequisites and repository layout

Install **JDK 17+**, **Apache Maven**, and **curl**. Bash is needed for the provided API-test and indexing benchmark scripts; Python 3 is needed for plotting and garage reports. Internet get right of entry to is needed to download books from Project Gutenberg and to remedy Maven dependencies on first construct.

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

**Pivotal:** The default relative paths are resolved towards every technique's **worling directory**. To keep away from developing separate datalakes/datamarts, configure **absolute paths shared with the aid of all techniques**. The example beneath uses the repository root because the working listing and units absolute paths explicitly.

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

**Replica counts:** The utility does no longer disclose a `REPLICAS` putting or a duplicate launcher. One example of each element is the documented baseline. Additional tactics could require one-of-a-kind listening ports and cautious managing of shared documents and Control kingdom; they may be **now not** demonstrated through this README.

**Thread settings:** Javalin's server thread swimming pools aren't exposed as an application surroundings-variable setting in those `App` classes. The repository does provide `JMH_THREADS_LIST` for **indexing benchmark threads**, no longer for production HTTP employee threads. The benchmark script defaults to `1 2 4`. Do now not interpret benchmark thread counts as procedure replica counts.

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

The YAML documents incorporate the whole HTTP contract information for the 3 services. **No Control OpenAPI YAML file turned into located within the furnished ZIP**; its endpoints are documented here from `ControlApp.Java`. Non-HTTP contracts encompass the shared datalake of completion marker `.Entire`, the datamart files, and Control's JSON nation report. All 4 processes run at the same Stage 2 host.

## 6. Interface validation and unit tests

Run the supplied HTTP smoke-check scripts **after starting the services**:

```bash
bash ingestion-service/scripts/test-api.sh http://localhost:7001
INDEXING_BASE_URL=http://localhost:7002 BOOK_ID=1342 bash indexing-service/scripts/validate_api.sh
bash search-service/scripts/test-api.sh http://localhost:7003
```

The Indexing check assumes book 1342 has already been ingested. Its script **modifies the index** and runs a complete rebuild. The Ingestion script performs a actual Gutenberg down load and consequently needs net get entry to. Search's script assessments regular and invalid queries; its output depends at the to be had index. Control may be smoke-examined with the `/popularity`, `/technique/book_id`, and `/state/book_id` instructions in Section five; no separate Control validation shell script is present.

Run Java checks from every provider directory with `mvn test`. Unit/API tests are located under every module's `src/take a look at/java/` directory. These checks exercise conduct; the lifestyles of scripts does now not, by itself, show they bypass in each surroundings.

## 7. Benchmarks and results

Benchmark source files are under each service's `src/test/java/` directory; output directories are `*/benchmarks/results/`.

**Indexing:** The repository provides a complete shell entry point. From the root:

```bash
(cd indexing-service && JMH_THREADS_LIST='1 2 4' bash scripts/run_benchmarks.sh)
```

The script compiles assessments, builds the take a look at classpath, invokes JMH at 1, 2 and four benchmark threads, writes `jmh-indexing-t1.Csv`, `jmh-indexing-t2.Csv`, and `jmh-indexing-t4.Csv`, and calls `scripts/plot_results.Py`. **Note:** this script calls `&quot;$JAVA_HOME/bin/java&quot;`; set `JAVA_HOME` for your JDK set up earlier than going for walks it. Python plotting dependencies should additionally be set up.

**Ingestion and Search:** Each has a JMH `BenchmarkRunner` under `src/check/java/.../benchmark/`, but there is **no equal benchmark shell script** in the provided repository. The following Maven instructions build the take a look at classpath and invoke the JMH predominant class immediately (run every from its carrier listing):

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

**Measurement provenance:** Do no longer count on that a CSV named `jmh-results.Csv` changed into generated by way of JMH. Verify its producer, configuration, gadgets, and raw output before reporting it as a JMH measurement. The Ingestion CSV has formerly been related to local timing measurements in place of tested JMH execution.

The Ingestion storage-file helper is `ingestion-service/scripts/storage-file.Py`; test its CLI utilization earlier than strolling it. Search plotting code is in `search-service/scripts/plot-benchmarks.Py`. These scripts are break away the JMH benchmark runner lessons.

## 8. Concurrency, storage and known limitations

- Ingestion writes `header.Txt` and `body.Txt` into `DATALAKE_PATH/&lt;book_id&gt;/`; the `.Entire` marker indicates that a ebook is ready for Indexing. Per-ebook locking protects concurrent requests **inner one Ingestion method**.
- Indexing reads finished books and publishes metadata/inverted-index facts in the datamart. Search consumes those files; its behavior relies upon on the datamart being gift and updated.
- Control shops pipeline states in `CONTROL_STATE_PATH` and serializes reproduction requests per ebook **within one Control procedure**. A failed request is marked `FAILED`; calling the method endpoint again can retry the workflow, but there may be no confirmed history retry scheduler.
- A unmarried-machine filesystem is a Stage 2 assumption. Multiple replicas sharing documents require additional synchronization and consistency work; they have to no longer be launched as even though reproduction protection had been already assured.
- The supplied repository does now not display a production thread-pool configuration interface, a multi-host deployment, or a comprehensive automated Control API settlement suite.


## 9. Stage 3 directions

Stage 3 needs to update local-only assumptions with specific go-gadget communication and shared/allotted storage, mission ownership, failure recuperation, secure booklet of latest index variations, provider discovery, and repeatable integration/load tests. Measure coordination overhead and resource usage below controlled workloads before drawing conclusions about reproduction scaling.
