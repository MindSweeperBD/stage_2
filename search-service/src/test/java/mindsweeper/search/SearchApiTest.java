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
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchApiTest {

    private static final Gson JSON = new Gson();

    @TempDir
    Path temp;

    @Test
    void restEndpointsFollowContract() throws Exception {
        Path dbPath = temp.resolve("metadata.db");
        Path indexPath = temp.resolve("inverted_index.json");

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
                    (5, 'Robinson Crusoe', 'Daniel Defoe', 'en', 1719, 'book_datalake/5/body.txt'),
                    (1342, 'Pride and Prejudice', 'Jane Austen', 'en', 1813, 'book_datalake/1342/body.txt')
                    """);
        }

        Files.writeString(
                indexPath,
                JSON.toJson(Map.of(
                        "adventure", List.of(5, 1342),
                        "island", List.of(5)
                )),
                StandardCharsets.UTF_8
        );

        DatamartStore store = new DatamartStore(dbPath, indexPath);
        SearchEngine engine = new SearchEngine(store);
        Javalin app = new App(store, engine).createServer().start(0);

        try {
            int port = app.port();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> statusRes = get(client, port, "/status");
            assertEquals(200, statusRes.statusCode());
            assertTrue(statusRes.body().contains("\"running\""));
            assertTrue(statusRes.body().contains("\"indexed_books\":2"));

            HttpResponse<String> searchRes = get(
                    client, port, "/search?q=adventure&language=en&offset=0&limit=20"
            );
            assertEquals(200, searchRes.statusCode());
            assertTrue(searchRes.body().contains("\"total\":2"));
            assertTrue(searchRes.body().contains("Robinson Crusoe"));
            assertTrue(searchRes.body().contains("Pride and Prejudice"));

            HttpResponse<String> combinedRes = get(
                    client, port, "/search?q=adventure&author=Austen&language=en&year=1813"
            );
            assertEquals(200, combinedRes.statusCode());
            assertTrue(combinedRes.body().contains("\"total\":1"));
            assertTrue(combinedRes.body().contains("\"book_id\":1342"));

            HttpResponse<String> emptyRes = get(
                    client, port, "/search?q=spaceship"
            );
            assertEquals(200, emptyRes.statusCode());
            assertTrue(emptyRes.body().contains("\"total\":0"));
            assertTrue(emptyRes.body().contains("\"results\":[]"));

            assertEquals(400, get(client, port, "/search").statusCode());
            assertEquals(400, get(client, port, "/search?q=").statusCode());
            assertEquals(400, get(client, port, "/search?q=adventure&offset=-1").statusCode());
            assertEquals(400, get(client, port, "/search?q=adventure&limit=0").statusCode());
            assertEquals(400, get(client, port, "/search?q=adventure&year=notanumber").statusCode());

            HttpResponse<String> bookRes = get(client, port, "/books/1342");
            assertEquals(200, bookRes.statusCode());
            assertTrue(bookRes.body().contains("Jane Austen"));

            assertEquals(404, get(client, port, "/books/9999").statusCode());
            assertEquals(400, get(client, port, "/books/invalid").statusCode());

            HttpResponse<String> filtersRes = get(client, port, "/search/filters");
            assertEquals(200, filtersRes.statusCode());
            assertTrue(filtersRes.body().contains("Jane Austen"));
            assertTrue(filtersRes.body().contains("1813"));

        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> get(
            HttpClient client,
            int port,
            String path
    ) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
