package com.mindsweeper.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class App {

    private static final Gson JSON = new Gson();

    private final IngestionStore store;
    private final BookDownloader downloader;

    private final ConcurrentHashMap<Integer, Object> locks =
            new ConcurrentHashMap<>();

    public App(IngestionStore store, BookDownloader downloader) {
        this.store = store;
        this.downloader = downloader;
    }

    public Javalin createServer() {

        Javalin app = Javalin.create(config ->
                config.http.defaultContentType = "application/json"
        );

        app.get("/status", ctx ->
                json(ctx, 200, Map.of(
                        "service", "ingestion",
                        "status", "running"
                ))
        );

        app.get("/ingest/list", ctx -> {
            var ids = store.list();

            json(ctx, 200, Map.of(
                    "count", ids.size(),
                    "books", ids
            ));
        });

        app.get("/ingest/stats", ctx ->
                json(ctx, 200, store.stats())
        );

        app.get("/ingest/status/{book_id}", ctx -> {

            int id = parseId(ctx.pathParam("book_id"));

            String status = store.isComplete(id)
                    ? "available"
                    : "not_available";

            json(ctx, 200, Map.of(
                    "book_id", id,
                    "status", status
            ));
        });

        app.post("/ingest/{book_id}", ctx -> {

            int id = parseId(ctx.pathParam("book_id"));

            Object lock = locks.computeIfAbsent(
                    id, key -> new Object()
            );

            synchronized (lock) {

                if (store.isComplete(id)) {

                    json(ctx, 200, Map.of(
                            "book_id", id,
                            "status", "already_downloaded",
                            "path", store.bookDir(id).toString()
                    ));

                    return;
                }

                try {

                    BookDownloader.Book book =
                            downloader.download(id);

                    Path directory = store.save(id, book);

                    json(ctx, 200, Map.of(
                            "book_id", id,
                            "status", "downloaded",
                            "path", directory.toString()
                    ));

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();

                    json(ctx, 503, Map.of(
                            "error", "interrupted"
                    ));

                } catch (IOException e) {

                    json(ctx, 502, Map.of(
                            "error", "ingestion_failed",
                            "message", e.getMessage()
                    ));
                }
            }
        });

        app.exception(IllegalArgumentException.class,
                (e, ctx) -> json(ctx, 400, Map.of(
                        "error", "invalid_book_id",
                        "message", e.getMessage()
                ))
        );

        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();

            json(ctx, 500, Map.of(
                    "error", "internal_error"
            ));
        });

        return app;
    }

    private static void json(
            Context ctx,
            int status,
            Object value
    ) {

        ctx.status(status)
                .contentType("application/json")
                .result(JSON.toJson(value));
    }

    private static int parseId(String value) {

        try {

            int id = Integer.parseInt(value);

            if (id > 0) {
                return id;
            }

        } catch (NumberFormatException ignored) {
        }

        throw new IllegalArgumentException(
                "book_id must be a positive 32-bit integer"
        );
    }

    public static void main(String[] args) {

        int port = Integer.parseInt(
                System.getenv().getOrDefault(
                        "INGESTION_PORT", "7001"
                )
        );

        Path root = Path.of(
                System.getenv().getOrDefault(
                        "DATALAKE_PATH", "book_datalake"
                )
        );

        String url = System.getenv().getOrDefault(
                "GUTENBERG_BASE_URL",
                "https://www.gutenberg.org/cache/epub/"
        );

        App service = new App(
                new IngestionStore(root),
                new BookDownloader(url)
        );

        service.createServer().start(port);
    }
}
