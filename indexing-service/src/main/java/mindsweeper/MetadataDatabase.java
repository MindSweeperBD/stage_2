package mindsweeper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

public final class MetadataDatabase {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS books (
                book_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                language TEXT NOT NULL,
                year INTEGER,
                body_path TEXT NOT NULL
            )
            """;

    private static final String UPSERT = """
            INSERT OR REPLACE INTO books
                (book_id, title, author, language, year, body_path)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final Path databasePath;
    private final String databaseUrl;

    public MetadataDatabase(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.databaseUrl = "jdbc:sqlite:" + this.databasePath;
    }

    public Path databasePath() {
        return databasePath;
    }

    public void createDatabase() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)");
        }
    }

    Connection openConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    void apply(Connection connection,
               Collection<BookMetadata> metadata,
               boolean replaceAll) throws SQLException {
        if (replaceAll) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM books");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            for (BookMetadata book : metadata) {
                statement.setInt(1, book.bookId());
                statement.setString(2, book.title());
                statement.setString(3, book.author());
                statement.setString(4, book.language());
                if (book.year() == null) {
                    statement.setNull(5, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(5, book.year());
                }
                statement.setString(6, book.bodyPath());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public Map<Integer, BookMetadata> loadAll() throws SQLException {
        String sql = """
                SELECT book_id, title, author, language, year, body_path
                FROM books
                ORDER BY book_id
                """;
        Map<Integer, BookMetadata> result = new TreeMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                BookMetadata metadata = new BookMetadata(
                        rows.getInt("book_id"),
                        rows.getString("title"),
                        rows.getString("author"),
                        rows.getString("language"),
                        rows.getInt("year"),
                        rows.getString("body_path")
                );
                result.put(metadata.bookId(), metadata);
            }
        }
        return result;
    }
}
