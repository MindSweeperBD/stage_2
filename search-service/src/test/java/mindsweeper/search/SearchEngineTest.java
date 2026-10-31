package mindsweeper.search;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTest {

    private static final Gson JSON = new Gson();

    @TempDir
    Path tempDir;

    private Path dbPath;
    private Path indexPath;
    private DatamartStore store;
    private SearchEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("metadata.db");
        indexPath = tempDir.resolve("inverted_index.json");

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

            stmt.execute("""
                    INSERT INTO books (book_id, title, author, language, year, body_path) VALUES
                    (5, 'Robinson Crusoe', 'Daniel Defoe', 'English', 1719, 'book_datalake/5/body.txt'),
                    (12, 'Treasure Island Adventure', 'Robert Louis Stevenson', 'en', 1883, 'book_datalake/12/body.txt'),
                    (1342, 'Pride and Prejudice', 'Jane Austen', 'English', 1813, 'book_datalake/1342/body.txt'),
                    (2000, 'Don Quijote de la Mancha', 'Miguel de Cervantes', 'Spanish', 1605, 'book_datalake/2000/body.txt')
                    """);
        }

        Map<String, List<Integer>> indexData = Map.of(
                "adventure", List.of(5, 12, 1342, 2000),
                "island", List.of(5, 12),
                "love", List.of(1342)
        );

        Files.writeString(indexPath, JSON.toJson(indexData), StandardCharsets.UTF_8);

        store = new DatamartStore(dbPath, indexPath);
        engine = new SearchEngine(store);
    }

    @Test
    void ranksByTermMatchesAndTitleHits() throws Exception {
        SearchEngine.SearchResponse response = engine.search(
                new SearchEngine.SearchQuery("adventure island", null, null, null, 0, 20)
        );

        assertEquals(4, response.total());
        assertEquals(4, response.results().size());
        assertEquals(12, response.results().get(0).book_id());
        assertEquals(5, response.results().get(1).book_id());
    }

    @Test
    void appliesIndividualAndCombinedFilters() throws Exception {
        SearchEngine.SearchResponse byLang = engine.search(
                new SearchEngine.SearchQuery("adventure", null, "en", null, 0, 20)
        );
        assertEquals(3, byLang.total());

        SearchEngine.SearchResponse combined = engine.search(
                new SearchEngine.SearchQuery("adventure", "Austen", "en", 1813, 0, 20)
        );
        assertEquals(1, combined.total());
        assertEquals(1342, combined.results().get(0).book_id());
        assertEquals("en", combined.results().get(0).language());
        assertEquals(1813, combined.results().get(0).year());
    }

    @Test
    void supportsPaginationCorrectly() throws Exception {
        SearchEngine.SearchResponse page1 = engine.search(
                new SearchEngine.SearchQuery("adventure", null, null, null, 0, 2)
        );
        assertEquals(4, page1.total());
        assertEquals(2, page1.results().size());

        SearchEngine.SearchResponse page2 = engine.search(
                new SearchEngine.SearchQuery("adventure", null, null, null, 2, 2)
        );
        assertEquals(4, page2.total());
        assertEquals(2, page2.results().size());
        assertNotEquals(page1.results().get(0).book_id(), page2.results().get(0).book_id());
    }

    @Test
    void worksWithStage1SchemaWithoutYearColumn() throws Exception {
        Path legacyDb = tempDir.resolve("legacy_metadata.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    CREATE TABLE books (
                        book_id INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        language TEXT NOT NULL,
                        body_path TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    INSERT INTO books VALUES (5, 'Robinson Crusoe', 'Daniel Defoe', 'English', 'path/5')
                    """);
        }

        DatamartStore legacyStore = new DatamartStore(legacyDb, indexPath);
        SearchEngine legacyEngine = new SearchEngine(legacyStore);

        SearchEngine.SearchResponse res = legacyEngine.search(
                new SearchEngine.SearchQuery("adventure", null, "en", null, 0, 20)
        );
        assertEquals(1, res.total());
        assertNull(res.results().get(0).year());
    }

    @Test
    void reloadsIndexWhenUpdatedOnDisk() throws Exception {
        assertEquals(0, engine.search(
                new SearchEngine.SearchQuery("shipwreck", null, null, null, 0, 20)
        ).total());

        Map<String, List<Integer>> updatedIndex = Map.of(
                "adventure", List.of(5, 12, 1342, 2000),
                "shipwreck", List.of(5)
        );
        Files.writeString(indexPath, JSON.toJson(updatedIndex), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(
                indexPath,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000)
        );

        assertEquals(1, engine.search(
                new SearchEngine.SearchQuery("shipwreck", null, null, null, 0, 20)
        ).total());
    }

    @Test
    void concurrentSearchesAreThreadSafe() throws Exception {
        var threads = new ArrayList<Thread>();
        var failures = new ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    for (int j = 0; j < 25; j++) {
                        SearchEngine.SearchResponse res = engine.search(
                                new SearchEngine.SearchQuery("adventure island", null, "en", null, 0, 10)
                        );
                        assertEquals(3, res.total());
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertTrue(failures.isEmpty(), failures.toString());
    }
}
