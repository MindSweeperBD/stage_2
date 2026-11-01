package com.mindsweeper.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ServiceClient {

    private final HttpClient httpClient;

    private final String ingestionUrl;
    private final String indexingUrl;
    private final String searchUrl;

    public ServiceClient() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        this.ingestionUrl = System.getenv()
                .getOrDefault("INGESTION_URL", "http://localhost:7001");

        this.indexingUrl = System.getenv()
                .getOrDefault("INDEXING_URL", "http://localhost:7002");

        this.searchUrl = System.getenv()
                .getOrDefault("SEARCH_URL", "http://localhost:7003");
    }

    private HttpResponse<String> sendGet(String url)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> sendPost(String url)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    public void ingestBook(int bookId)
            throws IOException, InterruptedException {

        HttpResponse<String> response = sendPost(
                ingestionUrl + "/ingest/" + bookId
        );

        if (response.statusCode() != 200) {
            throw new IOException("Ingestion failed: " + response.body());
        }
    }

    public boolean isBookIngested(int bookId)
            throws IOException, InterruptedException {

        HttpResponse<String> response = sendGet(
                ingestionUrl + "/ingest/status/" + bookId
        );

        if (response.statusCode() != 200) {
            throw new IOException("Could not check ingestion status");
        }

        JsonObject json = JsonParser.parseString(response.body())
                .getAsJsonObject();

        return json.has("status")
                && json.get("status").getAsString().equals("available");
    }

    public void indexBook(int bookId)
            throws IOException, InterruptedException {

        HttpResponse<String> response = sendPost(
                indexingUrl + "/index/update/" + bookId
        );

        if (response.statusCode() != 200) {
            throw new IOException("Indexing failed: " + response.body());
        }
    }

    public boolean isBookIndexed(int bookId)
            throws IOException, InterruptedException {

        HttpResponse<String> response = sendGet(
                searchUrl + "/books/" + bookId
        );

        if (response.statusCode() == 404) {
            return false;
        }

        if (response.statusCode() != 200) {
            throw new IOException("Could not verify indexed book");
        }

        return true;
    }
}
