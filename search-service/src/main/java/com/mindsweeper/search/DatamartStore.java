package com.mindsweeper.search;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public class DatamartStore {

    private static final Gson JSON = new Gson();
    private static final Type INDEX_TYPE =
            new TypeToken<Map<String, List<Integer>>>() {}.getType();

    private static final Map<String, String> LANGUAGE_TO_ISO = Map.ofEntries(
            Map.entry("english", "en"),
            Map.entry("en", "en"),
            Map.entry("spanish", "es"),
            Map.entry("es", "es"),
            Map.entry("french", "fr"),
            Map.entry("fr", "fr"),
            Map.entry("german", "de"),
            Map.entry("de", "de"),
            Map.entry("italian", "it"),
            Map.entry("it", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("pt", "pt"),
            Map.entry("dutch", "nl"),
            Map.entry("nl", "nl"),
            Map.entry("latin", "la"),
            Map.entry("la", "la")
    );

    private final Path metadataDbPath;
    private final Path invertedIndexPath;
    private final String jdbcUrl;

    private final Object indexLock = new Object();
    private volatile IndexSnapshot currentSnapshot = IndexSnapshot.empty();

    public record BookRecord(
            int book_id,
            String title,
            String author,
            String language,
            Integer year
    ) {}

    public record FilterOptions(
            List<String> authors,
            List<String> languages,
            List<Integer> years
    ) {}

    public record StoreStats(
            int indexed_books,
            int indexed_terms,
            boolean metadata_db_available,
            boolean inverted_index_available
    ) {}

    private record IndexSnapshot(
            long lastModifiedMillis,
            long fileSize,
            Map<String, Set<Integer>> postings
    ) {
        static IndexSnapshot empty() {
            return new IndexSnapshot(-1L, -1L, Map.of());
        }
    }

    public DatamartStore(Path datamartDir) {
        this(
                datamartDir.resolve("metadata.db"),
                datamartDir.resolve("inverted_index.json")
        );
    }

    public DatamartStore(Path metadataDbPath, Path invertedIndexPath) {
        this.metadataDbPath = metadataDbPath.toAbsolutePath().normalize();
        this.invertedIndexPath = invertedIndexPath.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.metadataDbPath;
    }

    public Set<Integer> getPostings(String term) throws IOException {
        Map<String, Set<Integer>> index = loadOrRefreshIndex();
        return index.getOrDefault(term, Set.of());
    }

    public Map<Integer, BookRecord> getBooksByIds(Collection<Integer> bookIds)
            throws SQLException {

        if (bookIds == null || bookIds.isEmpty() || !Files.isRegularFile(metadataDbPath)) {
            return Map.of();
        }

        try (Connection conn = openConnection()) {
            boolean hasYear = hasYearColumn(conn);
            String placeholders = String.join(",", Collections.nCopies(bookIds.size(), "?"));

            String sql = hasYear
                    ? "SELECT book_id, title, author, language, year FROM books WHERE book_id IN (" + placeholders + ")"
                    : "SELECT book_id, title, author, language FROM books WHERE book_id IN (" + placeholders + ")";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Integer id : bookIds) {
                    stmt.setInt(idx++, id);
                }

                Map<Integer, BookRecord> map = new HashMap<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        BookRecord record = mapRow(rs, hasYear);
                        map.put(record.book_id(), record);
                    }
                }
                return map;
            }
        }
    }

    public Optional<BookRecord> getBookById(int bookId) throws SQLException {
        if (!Files.isRegularFile(metadataDbPath)) {
            return Optional.empty();
        }

        try (Connection conn = openConnection()) {
            boolean hasYear = hasYearColumn(conn);
            String sql = hasYear
                    ? "SELECT book_id, title, author, language, year FROM books WHERE book_id = ?"
                    : "SELECT book_id, title, author, language FROM books WHERE book_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, bookId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs, hasYear));
                    }
                    return Optional.empty();
                }
            }
        }
    }

    public FilterOptions getAvailableFilters() throws SQLException {
        if (!Files.isRegularFile(metadataDbPath)) {
            return new FilterOptions(List.of(), List.of(), List.of());
        }

        Set<String> authors = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> languages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<Integer> years = new TreeSet<>();

        try (Connection conn = openConnection()) {
            boolean hasYear = hasYearColumn(conn);
            String sql = hasYear
                    ? "SELECT author, language, year FROM books"
                    : "SELECT author, language FROM books";

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String author = rs.getString("author");
                    if (author != null && !author.isBlank()) {
                        authors.add(author.trim());
                    }

                    String language = normalizeLanguageCode(rs.getString("language"));
                    if (!language.isBlank()) {
                        languages.add(language);
                    }

                    if (hasYear) {
                        int year = rs.getInt("year");
                        if (!rs.wasNull() && year > 0) {
                            years.add(year);
                        }
                    }
                }
            }
        }

        return new FilterOptions(
                List.copyOf(authors),
                List.copyOf(languages),
                List.copyOf(years)
        );
    }

    public StoreStats stats() throws IOException, SQLException {
        int terms = loadOrRefreshIndex().size();
        int books = 0;

        boolean dbExists = Files.isRegularFile(metadataDbPath);
        if (dbExists) {
            try (Connection conn = openConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM books");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    books = rs.getInt(1);
                }
            } catch (SQLException ignored) {
            }
        }

        return new StoreStats(
                books,
                terms,
                dbExists,
                Files.isRegularFile(invertedIndexPath)
        );
    }

    public static String normalizeLanguageCode(String rawLanguage) {
        if (rawLanguage == null || rawLanguage.isBlank()) {
            return "";
        }
        String clean = rawLanguage.trim().toLowerCase(Locale.ROOT);
        return LANGUAGE_TO_ISO.getOrDefault(clean, clean);
    }

    private Map<String, Set<Integer>> loadOrRefreshIndex() throws IOException {
        if (!Files.isRegularFile(invertedIndexPath)) {
            return Map.of();
        }

        long lastModified = Files.getLastModifiedTime(invertedIndexPath).toMillis();
        long size = Files.size(invertedIndexPath);

        IndexSnapshot snapshot = this.currentSnapshot;
        if (snapshot.lastModifiedMillis() == lastModified && snapshot.fileSize() == size) {
            return snapshot.postings();
        }

        synchronized (indexLock) {
            snapshot = this.currentSnapshot;
            if (snapshot.lastModifiedMillis() == lastModified && snapshot.fileSize() == size) {
                return snapshot.postings();
            }

            try (Reader reader = Files.newBufferedReader(invertedIndexPath, StandardCharsets.UTF_8)) {
                Map<String, List<Integer>> raw = JSON.fromJson(reader, INDEX_TYPE);
                if (raw == null || raw.isEmpty()) {
                    this.currentSnapshot = new IndexSnapshot(lastModified, size, Map.of());
                    return Map.of();
                }

                Map<String, Set<Integer>> immutableIndex = new HashMap<>(raw.size() * 2);
                for (Map.Entry<String, List<Integer>> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        immutableIndex.put(
                                entry.getKey().toLowerCase(Locale.ROOT),
                                Set.copyOf(entry.getValue())
                        );
                    }
                }

                Map<String, Set<Integer>> published = Collections.unmodifiableMap(immutableIndex);
                this.currentSnapshot = new IndexSnapshot(lastModified, size, published);
                return published;
            }
        }
    }

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA query_only = ON;");
            stmt.execute("PRAGMA busy_timeout = 3000;");
        } catch (SQLException ignored) {
        }
        return conn;
    }

    private static boolean hasYearColumn(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("PRAGMA table_info(books)");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                if ("year".equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BookRecord mapRow(ResultSet rs, boolean hasYear) throws SQLException {
        int id = rs.getInt("book_id");
        String title = Optional.ofNullable(rs.getString("title")).orElse("");
        String author = Optional.ofNullable(rs.getString("author")).orElse("");
        String language = normalizeLanguageCode(rs.getString("language"));

        Integer year = null;
        if (hasYear) {
            int y = rs.getInt("year");
            if (!rs.wasNull() && y > 0) {
                year = y;
            }
        }

        return new BookRecord(id, title, author, language, year);
    }
}
