package com.mindsweeper.search;

import com.google.gson.Gson;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class SearchBenchmark {

    private static final Gson JSON = new Gson();

    private SearchEngine engine;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path tempDir = Files.createTempDirectory("mindsweeper-search-jmh-");
        Path dbPath = tempDir.resolve("metadata.db");
        Path indexPath = tempDir.resolve("inverted_index.json");

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
                for (int i = 1; i <= 500; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "Adventure Book " + i);
                    ps.setString(3, "Author " + (i % 25));
                    ps.setString(4, (i % 5 == 0) ? "es" : "en");
                    ps.setInt(5, 1800 + (i % 150));
                    ps.setString(6, "book_datalake/" + i + "/body.txt");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }

        Map<String, List<Integer>> index = new HashMap<>();
        List<Integer> adventureDocs = new ArrayList<>();
        List<Integer> islandDocs = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            adventureDocs.add(i);
            if (i % 2 == 0) {
                islandDocs.add(i);
            }
        }
        index.put("adventure", adventureDocs);
        index.put("island", islandDocs);

        Files.writeString(indexPath, JSON.toJson(index), StandardCharsets.UTF_8);

        DatamartStore store = new DatamartStore(dbPath, indexPath);
        engine = new SearchEngine(store);
    }

    @Benchmark
    public List<String> tokenizeQuery() {
        return SearchEngine.tokenize("Adventure on the Mysterious Island! 1883");
    }

    @Benchmark
    public SearchEngine.SearchResponse keywordSearch() throws Exception {
        return engine.search(new SearchEngine.SearchQuery(
                "adventure island", null, null, null, 0, 20
        ));
    }

    @Benchmark
    public SearchEngine.SearchResponse filteredSearch() throws Exception {
        return engine.search(new SearchEngine.SearchQuery(
                "adventure island", "Author 4", "en", null, 0, 20
        ));
    }
}
