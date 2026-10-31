package com.mindsweeper.indexing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class IndexingServiceTest {
    private static final Type INDEX_TYPE = new TypeToken<TreeMap<String, List<Integer>>>() { }.getType();

    @TempDir
    Path temp;

    private Path datalake;
    private Path datamart;

    @BeforeEach
    void setUp() throws Exception {
        datalake = temp.resolve("book_datalake");
        datamart = temp.resolve("book_datamarts");
        Files.createDirectories(datalake);
    }

    @Test
    void updateWritesStageOneMetadataDatabaseAndMonolithicJson() throws Exception {
        writeBook(1342, "Pride and Prejudice", "Jane Austen", "English", 1996,
                "It is a truth universally acknowledged that the story begins.");

        IndexingService service = new IndexingService(datalake, datamart);
        IndexingService.UpdateResult result = service.updateBook(1342);

        assertEquals("updated", result.index());
        assertTrue(Files.isRegularFile(datamart.resolve("metadata.db")));
        assertTrue(Files.isRegularFile(datamart.resolve("inverted_index.json")));
        assertFalse(Files.exists(datamart.resolve("CURRENT")));
        assertFalse(Files.exists(datamart.resolve("versions")));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + datamart.resolve("metadata.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT book_id,title,author,language,year,body_path FROM books")) {
            assertTrue(rows.next());
            assertEquals(1342, rows.getInt("book_id"));
            assertEquals("Pride and Prejudice", rows.getString("title"));
            assertEquals("Jane Austen", rows.getString("author"));
            assertEquals("English", rows.getString("language"));
            assertEquals(1996, rows.getInt("year"));
            assertEquals(datalake.resolve("1342/body.txt").toAbsolutePath().toString(),
                    rows.getString("body_path"));
            assertFalse(rows.next());
        }

        Map<String, List<Integer>> index = loadJsonIndex();
        assertEquals(List.of(1342), index.get("universally"));
        assertEquals(List.of(1342), index.get("acknowledged"));
        assertFalse(index.containsKey("pride"), "Stage 1 tokenizes the body only");
        assertTrue(service.currentInvertedIndex().get("universally").contains(1342));
    }

    @Test
    void repeatedUpdateIsIdempotentAndDoesNotDuplicateBookIds() throws Exception {
        writeBook(1342, "Pride and Prejudice", "Jane Austen", "English", 1996, "love love story");
        IndexingService service = new IndexingService(datalake, datamart);

        IndexingService.UpdateResult first = service.updateBook(1342);
        IndexingService.UpdateResult second = service.updateBook(1342);

        assertEquals("updated", first.index());
        assertEquals("already_indexed", second.index());
        assertEquals(1, service.currentMetadata().size());
        assertEquals(List.of(1342), loadJsonIndex().get("love"));
    }

    @Test
    void updatingChangedBodyRemovesOldPostings() throws Exception {
        writeBook(10, "Book Ten", "Author A", "English", 1990, "alpha beta beta");
        IndexingService service = new IndexingService(datalake, datamart);
        service.updateBook(10);

        writeBook(10, "Book Ten", "Author A", "English", 1990, "gamma delta");
        IndexingService.UpdateResult result = service.updateBook(10);

        assertEquals("updated", result.index());
        Map<String, List<Integer>> index = loadJsonIndex();
        assertFalse(index.containsKey("alpha"));
        assertFalse(index.containsKey("beta"));
        assertEquals(List.of(10), index.get("gamma"));
        assertEquals(List.of(10), index.get("delta"));
    }

    @Test
    void updateRejectsBookWithoutCompletionMarker() throws Exception {
        Path directory = datalake.resolve("55");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("header.txt"), "Title: Partial", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("body.txt"), "Some text", StandardCharsets.UTF_8);

        IndexingService service = new IndexingService(datalake, datamart);
        assertThrows(BookNotReadyException.class, () -> service.updateBook(55));
        assertEquals(0, service.currentMetadata().size());
    }

    @Test
    void rebuildUsesOnlyNumericBookDirectoriesAndSkipsIncompleteBooks() throws Exception {
        writeBook(10, "Book Ten", "Author A", "English", 1990, "alpha beta");
        writeBook(20, "Book Twenty", "Author B", "Spanish", 1990, "beta gamma");
        Path partial = datalake.resolve("30");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve("body.txt"), "not complete", StandardCharsets.UTF_8);
        Path unrelated = datalake.resolve("not-a-book");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("body.txt"), "ignored", StandardCharsets.UTF_8);

        IndexingService service = new IndexingService(datalake, datamart);
        IndexingService.RebuildResult result = service.rebuild();

        assertEquals(2, result.booksProcessed());
        assertEquals(1, result.skippedIncomplete());
        assertEquals(2, service.currentMetadata().size());
        assertEquals(List.of(10, 20), loadJsonIndex().get("beta"));
    }

    @Test
    void concurrentDuplicateUpdatesDoNotCreateDuplicateRowsOrPostings() throws Exception {
        writeBook(777, "Concurrent Book", "Author", "English", 2006, "concurrent same text for every request");
        IndexingService service = new IndexingService(datalake, datamart);

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<IndexingService.UpdateResult>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> service.updateBook(777));
            }
            var results = executor.invokeAll(calls);
            long updated = 0;
            long already = 0;
            for (var result : results) {
                String status = result.get().index();
                if ("updated".equals(status)) updated++;
                if ("already_indexed".equals(status)) already++;
            }
            assertEquals(1, updated);
            assertEquals(15, already);
        } finally {
            executor.shutdownNow();
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + datamart.resolve("metadata.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) AS n FROM books")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("n"));
        }
        assertEquals(List.of(777), loadJsonIndex().get("concurrent"));
    }

    private Map<String, List<Integer>> loadJsonIndex() throws Exception {
        String json = Files.readString(datamart.resolve("inverted_index.json"), StandardCharsets.UTF_8);
        return new Gson().fromJson(json, INDEX_TYPE);
    }

    @Test
    void updatingBookUpdatesItsYear() throws Exception {
        writeBook(10, "Book Ten", "Author A", "English", 1990, "alpha beta");

        IndexingService service = new IndexingService(datalake, datamart);
        service.updateBook(10);

        writeBook(10, "Book Ten", "Author A", "English", 2000, "alpha beta");

        service.updateBook(10);

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + datamart.resolve("metadata.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT year FROM books WHERE book_id = 10")) {

            assertTrue(rows.next());
            assertEquals(2000, rows.getInt("year"));
        }
    }

    private void writeBook(int id, String title, String author, String language, int year, String body)
            throws Exception {
        Path directory = datalake.resolve(Integer.toString(id));
        Files.createDirectories(directory);
        String header = "Title: " + title
                + "\nAuthor: " + author
                + "\nRelease date: January 1, " + year
                + " [eBook #" + id + "]\n"
                + "Language: " + language
                + "\n";
        Files.writeString(directory.resolve("header.txt"), header, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("body.txt"), body, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".complete"), "complete\n", StandardCharsets.UTF_8);
    }
}
