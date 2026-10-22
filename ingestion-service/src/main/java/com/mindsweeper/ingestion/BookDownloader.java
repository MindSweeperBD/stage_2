package com.mindsweeper.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class BookDownloader {

    private static final String START =
            "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END =
            "*** END OF THE PROJECT GUTENBERG EBOOK";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Book(String header, String body) {}

    public Book download(int id)
            throws IOException, InterruptedException {

        String url = "https://www.gutenberg.org/cache/epub/"
                + id + "/pg" + id + ".txt";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8)
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Gutenberg returned HTTP "
                    + response.statusCode());
        }

        return split(response.body());
    }

    public static Book split(String text) throws IOException {

        int start = text.indexOf(START);
        int end = text.indexOf(END, Math.max(start, 0));

        if (start < 0 || end < 0 || end <= start) {
            throw new IOException(
                    "Missing or incorrectly ordered Gutenberg markers");
        }

        String header = text.substring(0, start);
        String body = text.substring(start + START.length(), end);

        if (body.isBlank()) {
            throw new IOException("Empty book body");
        }

        return new Book(header, body);
    }
}
