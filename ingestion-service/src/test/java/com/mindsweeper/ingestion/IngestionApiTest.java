package com.mindsweeper.ingestion;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IngestionApiTest {

    @TempDir
    Path temp;

    @Test
    void realHttpWorkflowWithLocalFakeGutenberg()
            throws Exception {

        HttpServer upstream = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        upstream.createContext("/", exchange -> {

            String text =
                    "Title: Test\n" +
                    "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n" +
                    "Hello\n" +
                    "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***";

            byte[] body = text.getBytes(
                    StandardCharsets.UTF_8
            );

            exchange.sendResponseHeaders(200, body.length);

            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        upstream.start();

        String baseUrl =
                "http://127.0.0.1:" +
                upstream.getAddress().getPort() +
                "/";

        Javalin app = new App(
                new IngestionStore(temp),
                new BookDownloader(baseUrl)
        ).createServer().start(0);

        try {

            int port = app.port();

            HttpClient client = HttpClient.newHttpClient();

            assertEquals(
                    200,
                    send(client, port, "GET", "/status")
                            .statusCode()
            );

            assertEquals(
                    400,
                    send(client, port, "POST", "/ingest/abc")
                            .statusCode()
            );

            HttpResponse<String> first = send(
                    client, port, "POST", "/ingest/1342"
            );

            assertEquals(200, first.statusCode());

            assertTrue(
                    first.body().contains("\"downloaded\"")
            );

            assertTrue(
                    send(client, port, "POST", "/ingest/1342")
                            .body()
                            .contains("already_downloaded")
            );

            assertTrue(
                    send(client, port, "GET",
                            "/ingest/status/1342")
                            .body()
                            .contains("available")
            );

            assertTrue(
                    send(client, port, "GET", "/ingest/list")
                            .body()
                            .contains("1342")
            );

            assertTrue(
                    send(client, port, "GET", "/ingest/stats")
                            .body()
                            .contains("bytes")
            );

        } finally {

            app.stop();
            upstream.stop(0);
        }
    }

    private static HttpResponse<String> send(
            HttpClient client,
            int port,
            String method,
            String path
    ) throws Exception {

        URI uri = URI.create(
                "http://127.0.0.1:" + port + path
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(
                        method,
                        HttpRequest.BodyPublishers.noBody()
                )
                .build();

        return client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
