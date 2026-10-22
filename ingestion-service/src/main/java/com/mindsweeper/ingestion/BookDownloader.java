package com.mindsweeper.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BookDownloader {

    private static final Pattern START = Pattern.compile(
            "(?im)^\\*{3}\\s*START OF (?:THE|THIS) " +
            "PROJECT GUTENBERG EBOOK[^\\r\\n]*\\*{3}\\s*$"
    );

    private static final Pattern END = Pattern.compile(
            "(?im)^\\*{3}\\s*END OF (?:THE|THIS) " +
            "PROJECT GUTENBERG EBOOK[^\\r\\n]*\\*{3}\\s*$"
    );

    private final HttpClient client;
    private final String baseUrl;

    public record Book(String header, String body) {}

    public BookDownloader() {
        this("https://www.gutenberg.org/cache/epub/");
    }

    public BookDownloader(String baseUrl) {

        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl
                : baseUrl + "/";

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Book download(int id)
            throws IOException, InterruptedException {

        if (id <= 0) {
            throw new IllegalArgumentException(
                    "book_id must be positive"
            );
        }

        URI uri = URI.create(
                baseUrl + id + "/pg" + id + ".txt"
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8
                )
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Source returned HTTP " +
                    response.statusCode()
            );
        }

        return split(response.body());
    }

    public static Book split(String text)
            throws IOException {

        Matcher start = START.matcher(text);

        if (!start.find()) {
            throw new IOException(
                    "Gutenberg START marker not found"
            );
        }

        Matcher end = END.matcher(text);

        if (!end.find(start.end())) {
            throw new IOException(
                    "Gutenberg END marker not found"
            );
        }

        String header = text.substring(
                0, start.start()
        ).strip();

        String body = text.substring(
                start.end(), end.start()
        ).strip();

        if (body.isBlank()) {
            throw new IOException("Book body is empty");
        }

        return new Book(header, body);
    }
}
