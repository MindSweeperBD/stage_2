package mindsweeper.indexing;

import io.javalin.Javalin;
import mindsweeper.App;
import mindsweeper.IndexingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppHttpTest {
    @TempDir
    Path temp;

    @Test
    void exposesUpdateAndStatusEndpointsAndWritesStageOneFiles() throws Exception {
        Path datalake = temp.resolve("book_datalake");
        Path datamart = temp.resolve("book_datamarts");
        Path book = datalake.resolve("1342");
        Files.createDirectories(book);
        Files.writeString(book.resolve("header.txt"),
                "Title: Pride and Prejudice\nAuthor: Jane Austen\nLanguage: English\n",
                StandardCharsets.UTF_8);
        Files.writeString(book.resolve("body.txt"), "A novel about pride and prejudice.",
                StandardCharsets.UTF_8);
        Files.writeString(book.resolve(".complete"), "complete\n", StandardCharsets.UTF_8);

        IndexingService service = new IndexingService(datalake, datamart);
        Javalin server = new App(service).createServer();
        int port = freePort();
        server.start(port);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest update = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/index/update/1342"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> updateResponse = client.send(update,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, updateResponse.statusCode());
            assertTrue(updateResponse.body().contains("\"index\": \"updated\""));

            HttpRequest status = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/index/status"))
                    .GET().build();
            HttpResponse<String> statusResponse = client.send(status,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, statusResponse.statusCode());
            assertTrue(statusResponse.body().contains("\"books_indexed\": 1"));
            assertTrue(Files.isRegularFile(datamart.resolve("metadata.db")));
            assertTrue(Files.isRegularFile(datamart.resolve("inverted_index.json")));
        } finally {
            server.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
