package com.mindsweeper.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class App {

    private static final Gson JSON = new Gson();

    public static void main(String[] args) {

        int port = Integer.parseInt(
                System.getenv().getOrDefault(
                        "INGESTION_PORT", "7001"));

        Path datalake = Path.of(
                System.getenv().getOrDefault(
                        "DATALAKE_PATH", "book_datalake"));

        IngestionStore store = new IngestionStore(datalake);
        BookDownloader downloader = new BookDownloader();

        ConcurrentHashMap<Integer, Object> locks =
                new ConcurrentHashMap<>();

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        });

        app.get("/status", ctx -> {
            ctx.result(JSON.toJson(Map.of(
                    "service", "ingestion",
                    "status", "running"
            )));
        });

        app.get("/ingest/list", ctx -> {
            var books = store.list();

            ctx.result(JSON.toJson(Map.of(
                    "count", books.size(),
                    "books", books
            )));
        });

        app.get("/ingest/status/{book_id}", ctx -> {

            int id = parseId(ctx.pathParam("book_id"));

            ctx.result(JSON.toJson(Map.of(
                    "book_id", id,
                    "status", store.isComplete(id)
                            ? "available" : "not_available"
            )));
        });

        app.post("/ingest/{book_id}", ctx -> {

            int id = parseId(ctx.pathParam("book_id"));

            Object lock = locks.computeIfAbsent(
                    id, key -> new Object());

            synchronized (lock) {

                if (store.isComplete(id)) {
                    ctx.result(JSON.toJson(Map.of(
                            "book_id", id,
                            "status", "already_downloaded",
                            "path", store.bookDir(id).toString()
                    )));
                    return;
                }

                try {
                    BookDownloader.Book book =
                            downloader.download(id);

                    Path path = store.save(id, book);

                    ctx.result(JSON.toJson(Map.of(
                            "book_id", id,
                            "status", "downloaded",
                            "path", path.toString()
                    )));

                } catch (java.io.IOException e) {
                    ctx.status(502).result(JSON.toJson(Map.of(
                            "error", "ingestion_failed",
                            "message", e.getMessage()
                    )));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    ctx.status(503).result(JSON.toJson(Map.of(
                            "error", "interrupted"
                    )));
                }
            }
        });

        app.exception(IllegalArgumentException.class,
                (e, ctx) -> {
                    ctx.status(400).result(JSON.toJson(Map.of(
                            "error", "invalid_book_id",
                            "message", e.getMessage()
                    )));
                });

        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();

            ctx.status(500).result(JSON.toJson(Map.of(
                    "error", "internal_error"
            )));
        });

        app.start(port);
    }

    private static int parseId(String raw) {

        try {
            int id = Integer.parseInt(raw);

            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
        }

        throw new IllegalArgumentException(
                "book_id must be a positive 32-bit integer");
    }
}
