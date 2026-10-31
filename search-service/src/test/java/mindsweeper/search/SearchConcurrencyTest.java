package mindsweeper.search;

import com.google.gson.Gson;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConcurrencyTest {

    private static final Gson JSON = new Gson();
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8};
    private static final int REQUESTS_PER_THREAD = 40;

    @TempDir
    Path temp;

    @Test
    void evaluateThreadScalingAndExportCsv() throws Exception {
        Path dbPath = temp.resolve("metadata.db");
        Path indexPath = temp.resolve("inverted_index.json");
        populateSampleDatamart(dbPath, indexPath);

        DatamartStore store = new DatamartStore(dbPath, indexPath);
        SearchEngine engine = new SearchEngine(store);
        Javalin app = new App(store, engine).createServer().start(0);

        List<String> csvLines = new ArrayList<>();
        csvLines.add("threads,total_requests,avg_latency_ms,p95_latency_ms,throughput_req_per_sec");

        try {
            int port = app.port();
            HttpClient client = HttpClient.newHttpClient();

            for (int threads : THREAD_COUNTS) {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Double> latenciesMs = Collections.synchronizedList(new ArrayList<>());
                Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

                long startBatchNanos = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                            try {
                                long t0 = System.nanoTime();
                                URI uri = URI.create(
                                        "http://127.0.0.1:" + port +
                                                "/search?q=adventure+island&language=en&offset=0&limit=20"
                                );
                                HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
                                HttpResponse<String> res = client.send(
                                        req, HttpResponse.BodyHandlers.ofString()
                                );
                                long elapsedNanos = System.nanoTime() - t0;

                                assertEquals(200, res.statusCode());
                                latenciesMs.add(elapsedNanos / 1_000_000.0);
                            } catch (Throwable e) {
                                errors.add(e);
                            }
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get();
                }
                pool.shutdown();

                long totalBatchNanos = System.nanoTime() - startBatchNanos;
                assertTrue(errors.isEmpty(), "Errors during concurrency test: " + errors);

                Collections.sort(latenciesMs);
                int totalRequests = latenciesMs.size();
                double avgMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double p95Ms = latenciesMs.get((int) Math.min(totalRequests - 1, Math.floor(totalRequests * 0.95)));
                double totalSeconds = totalBatchNanos / 1_000_000_000.0;
                double throughput = totalRequests / totalSeconds;

                String row = String.format(
                        Locale.US,
                        "%d,%d,%.3f,%.3f,%.2f",
                        threads, totalRequests, avgMs, p95Ms, throughput
                );
                csvLines.add(row);
                System.out.println("[ConcurrencyTest] " + row);
            }

        } finally {
            app.stop();
        }

        Path baseDir = Files.isDirectory(Path.of("search-service"))
                ? Path.of("search-service")
                : Path.of(".");
        Path resultsDir = baseDir.resolve("benchmarks/results");
        Files.createDirectories(resultsDir);
        Files.write(resultsDir.resolve("concurrency-results.csv"), csvLines, StandardCharsets.UTF_8);
    }

    private static void populateSampleDatamart(Path dbPath, Path indexPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    CREATE TABLE books (
                        book_id INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        language TEXT NOT NULL,
                        year INTEGER,
                        body_path TEXT NOT NULL
                    )
                    """);

            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO books VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                for (int i = 1; i <= 200; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "Book " + i);
                    ps.setString(3, "Author " + (i % 20));
                    ps.setString(4, "en");
                    ps.setInt(5, 1800 + (i % 100));
                    ps.setString(6, "book_datalake/" + i + "/body.txt");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }

        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            ids.add(i);
        }
        Map<String, List<Integer>> index = Map.of(
                "adventure", ids,
                "island", ids.subList(0, 100)
        );
        Files.writeString(indexPath, JSON.toJson(index), StandardCharsets.UTF_8);
    }
}
