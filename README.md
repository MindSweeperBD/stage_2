# Mindsweeper — Stage 2
## Building a Service-Oriented Search Engine

**Big Data | Grado en Ciencia e Ingeniería de Datos**  
**Universidad de Las Palmas de Gran Canaria**

## 1. Introduction

Mindsweeper is a seek engine task evolved as part of the Big Data direction.

Stage 1 targeted on building and comparing the information layer, which includes the datalake, metadata processing, inverted indexes, and datamarts.

Stage 2 extends this basis right into a **Service-Oriented Architecture (SOA)**. Instead of executing the whole pipeline as a monolithic software, the gadget separates its duties into impartial offerings that speak through described interfaces.

The objective is to develop a modular search engine capable of consuming books, indexing their content, and answering search queries while assisting concurrent requests and getting ready for destiny scalability.

All Stage 2 strategies run on a single device. Deployment across a couple of machines is reserved for Stage three.

## 2. System Architecture

The architecture consists of three core microservices:

| Service | Responsibility |
|---------|----------------|
| Ingestion Service | Downloads books and stores their contents in the datalake. |
| Indexing Service | Processes book content and metadata, builds inverted indexes, and maintains datamarts. |
| Search Service | Executes keyword queries, applies metadata filters, and returns ranked results. |

The architecture also requires a coordination mechanism to manage dependencies between services.

A dedicated **Control Module** can perform this responsibility, although other strategies such as event-driven communication, scheduled execution, or shared-state coordination are also supported by the assignment.

### Data Processing Workflow

```text
              Project Gutenberg
                      |
                      v
             +------------------+
             | Ingestion Service|
             +------------------+
                      |
                      v
                 Datalake
                      |
                      v
             +------------------+
             | Indexing Service |
             +------------------+
                      |
                      v
                 Datamarts
                      |
                      v
             +------------------+
             |  Search Service  |<---- HTTP Client
             +------------------+
                      |
                      v
                Search Results
```

The coordination mechanism ensures that books are indexed only after ingestion has completed and that searches use consistent, published indexes.

## 3. Technologies

Stage 2 uses the following technologies:

- **Java 17:** Main implementation language.
- **Maven:** Dependency management and builds.
- **Javalin:** Lightweight framework for REST APIs.
- **Gson / JSON:** Structured communication between services.
- **JUnit:** Automated testing.
- **JMH:** Java performance benchmarking.
- **HTTP/REST:** Service interfaces.
- **Project Gutenberg:** Source of public-domain books.

Each microservice is designed as an independent Maven application.

## 4. Microservices

### 4.1 Ingestion Service

The Ingestion Service retrieves books from Project Gutenberg and shops them in the datalake.

Its duties include:

- Downloading books using their identifiers.
- Separating headers and e-book bodies.
- Organizing the downloaded content.
- Recording a hit ingestion.
- Preventing incomplete books from being treated as available.
- Exposing ingestion and status endpoints.

Main API operations:

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/ingest/{book_id}` | Downloads and stores a book. |
| GET | `/ingest/status/{book_id}` | Checks the ingestion status of a book. |
| GET | `/ingest/list` | Lists successfully ingested books. |

The service also provides operational endpoints for monitoring.

### 4.2 Indexing Service

The Indexing Service transforms stored books into searchable facts structures.

Its duties include:

- Reading completed books from the datalake.
- Extracting and processing metadata.
- Processing e book our bodies.
- Building and updating inverted indexes.
- Maintaining the datamarts.
- Avoiding duplicate entries while books are reindexed.
- Preserving consistency throughout index updates.

The challenge defines the subsequent example endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/index/update/{book_id}` | Updates the index for one book. |
| POST | `/index/rebuild` | Rebuilds the complete index. |
| GET | `/index/status` | Returns indexing statistics. |

### 4.3 Search Service

The Search Service affords the interface thru which clients retrieve facts.

Its obligations include:

- Processing key-word queries.
- Applying optionally available metadata filters.
- Supporting blended filters.
- Ranking search outcomes.
- Supporting pagination.
- Returning established JSON responses.
- Handling concurrent requests.

The major seek interface is:

```http
GET /search?q={term}&author={name}&language={code}&year={YYYY}&offset={offset}&limit={limit}
```

Parameters:

| Parameter | Required | Description |
|-----------|----------|-------------|
| `q` | Yes | Search term. |
| `author` | No | Author filter. |
| `language` | No | Language filter. |
| `year` | No | Publication-year filter. |
| `offset` | No | Pagination starting position. |
| `limit` | No | Maximum number of returned results. |

A successful search returns JSON containing the query, applied filters, total number of matches, pagination information, and matching books.

A query with no matches returns an empty results list. Invalid parameters must produce documented errors.

## 5. Service Coordination

Stage 2 calls for an specific mechanism for coordinating ingestion, indexing, and search.

The structure need to guarantee that:

1. A e book is not indexed before ingestion finishes.
2. Failed operations may be detected and retried.
3. Repeated requests do no longer create reproduction records.
4. Concurrent operations do now not corrupt shared documents or indexes.
5. Search requests best get right of entry to steady, posted index records.

Possible coordination techniques include:

- Classical orchestration via a Control Module.
- Event-pushed choreography using a message broking.
- Scheduled execution with continual readiness exams.
- Shared-memory coordination with inter-method synchronization.

The chosen strategy must outline how services exchange facts, determine completion, and get over disasters.

## 6. Concurrency and Replication

Stage 2 distinguishes between two forms of concurrency:

**Multithreading:** Multiple threads execute within the same service process.

**Replication:** Multiple independent processes provide the same logical service.

The gadget must keep in mind:

- Concurrent HTTP requests.
- Shared useful resource access.
- Duplicate processing.
- Synchronization among employees.
- Conflicting writes.
- CPU, memory, and disk rivalry.
- Service screw ups and healing.

Although all additives execute on one machine, the structure should become aware of which operations should benefit from parallel execution or additional replicas.

## 7. REST API Design

The system distinguishes between two API categories.

### Front-End-Facing API

Exposed by means of the Search Service to clients.

It provides key-word search, metadata filtering, rating, and pagination with out requiring customers to access the datalake or datamarts at once.

A graphical the front-stop is not required for Stage 2; HTTP clients may be used to validate this interface.

### Internal APIs

Used for communique among offerings and coordination components.

These interfaces manipulate ingestion, indexing, repute tests, and different operations required by using the processing pipeline.

API specs need to record:

- HTTP strategies and routes.
- Parameters and validation guidelines.
- Request and response formats.
- HTTP status codes.
- Expected errors.
- Completion and failure semantics.
- Retry and idempotency behavior.
- Intended clients.

OpenAPI YAML or JSON is the desired specification format.

## 8. Data Storage

The system uses two main storage layers.

### Datalake

Stores downloaded e-book statistics, consisting of headers and bodies.

Books need to most effective emerge as to be had to downstream services once ingestion has completed effectively.

### Datamarts

Stores the established statistics generated with the aid of indexing.

These structures encompass e-book metadata and inverted indexes used to execute seek queries effectively.

The Indexing Service connects the datalake and datamarts, at the same time as the Search Service consumes the posted indexes.

## 9. Project Structure

The project is organized around independent microservices.

```text
stage_2/
├── README.md
├── ingestion-service/
│   ├── pom.xml
│   ├── api/
│   ├── docs/
│   ├── benchmarks/
│   ├── scripts/
│   └── src/
├── indexing-service/
│   ├── pom.xml
│   └── src/
└── search-service/
    ├── pom.xml
    └── src/
```

This structure represents the intended group organization. Individual service directories are added as their implementations are integrated.

If a dedicated Control Module is selected, its implementation and configuration should also be included in the repository.

## 10. Building and Running

### Requirements

- Java Development Kit 17 or newer.
- Apache Maven.
- Python 3 for supporting scripts where applicable.
- Internet access for downloading Gutenberg books.

Each service is built independently.

Example:

```bash
cd ingestion-service
mvn clean package
```

The resulting executable JAR can be started using:

```bash
java -jar target/ingestion-service.jar
```

Other services follow their own Maven configurations and documented startup commands.

Service ports, storage paths, and communication endpoints should be configurable to support reproducible local deployment.

## 11. Testing and Validation

Testing is an essential part of Stage 2.

### Functional Testing

The challenge must validate:

- Successful and unsuccessful ingestion.
- Completion and readiness checks.
- Index updates and rebuilding.
- Keyword searches.
- Individual metadata filters.
- Combined filters.
- Empty seek results.
- Invalid requests.
- Pagination.
- Service coordination and failure dealing with.

Automated tests and executable HTTP examples must show the conduct of the carried out interfaces.

### Performance Evaluation

Stage 2 additionally evaluates gadget overall performance below one-of-a-kind workloads.

Relevant measurements consist of:

- Ingestion processing time.
- Index construction and replace time.
- Search reaction latency.
- Request throughput.
- Concurrent request coping with.
- Memory and storage intake.
- Performance underneath distinct thread or replica configurations.

Benchmarking results need to include the experimental configuration, measured values, and analysis of variability.

The assignment have to become aware of bottlenecks and provide an explanation for which architectural additives should advantage from parallelization or replication.

## 12. Scalability and Stage 3

Stage 2 establishes the foundation for a scalable search engine however does no longer put into effect a multi-gadget cluster.

Its single-gadget deployment introduces limitations concerning:

- Shared CPU and memory assets.
- Local filesystem dependencies.
- Disk I/O rivalry.
- Local synchronization mechanisms.
- Communication via neighborhood community addresses.

Stage three will make bigger the architecture to a cluster.

This transition will require reviewing service discovery, dispensed garage, mission distribution, coordination, community disasters, and consistency among service replicas.

## 13. Conclusion

Mindsweeper Stage 2 transforms the statistics-processing additives advanced in Stage 1 into a modular service-orientated seek engine.

The structure separates ingestion, indexing, and looking into cooperating offerings with documented interfaces and explicit coordination necessities.

Beyond useful correctness, this level emphasizes concurrency, reproducibility, overall performance assessment, fault managing, and the identification of scalability limitations.

These foundations prepare Mindsweeper for disbursed deployment in Stage three.oundations prepare Mindsweeper for distributed deployment in Stage 3.
