package mindsweeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class App {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private final IndexingService indexingService;

    public App(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    public Javalin createServer() {
        Javalin app = Javalin.create(config ->
                config.http.defaultContentType = "application/json"
        );

        getServiceStatus(app);
        indexOrUpdateBook(app);
        indexDatalake(app);
        getIndexStatus(app);

        throwBookNotReadyError(app);
        throwInvalidBookIdError(app);
        throwIndexingIoError(app);
        throwInternalError(app);

        return app;
    }

    private static void getServiceStatus(Javalin app) {
        app.get("/status", ctx -> json(ctx, 200, Map.of(
                "service", "indexing",
                "status", "running"
        )));
    }

    private void indexOrUpdateBook(Javalin app) {
        app.post("/index/update/{book_id}", ctx -> {
            int id = parseId(ctx.pathParam("book_id"));
            IndexingService.UpdateResult result = indexingService.updateBook(id);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("book_id", result.bookId());
            response.put("index", result.index());
            response.put("version", result.version());
            response.put("indexed_at", result.indexedAt());
            response.put("unique_terms", result.uniqueTerms());
            json(ctx, 200, response);
        });
    }

    private void indexDatalake(Javalin app) {
        app.post("/index/rebuild", ctx -> {
            IndexingService.RebuildResult result = indexingService.rebuild();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("books_processed", result.booksProcessed());
            response.put("skipped_incomplete", result.skippedIncomplete());
            response.put("version", result.version());
            response.put("elapsed_time_ms", result.elapsedTimeMs());
            response.put("elapsed_time", result.elapsedTime());
            json(ctx, 200, response);
        });
    }

    private void getIndexStatus(Javalin app) {
        app.get("/index/status", ctx -> {
            IndexingService.IndexStatus status = indexingService.status();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("books_indexed", status.booksIndexed());
            response.put("last_update", status.lastUpdate());
            response.put("index_size_MB", status.indexSizeMB());
            response.put("index_size_bytes", status.indexSizeBytes());
            response.put("index_version", status.version());
            response.put("unique_terms", status.uniqueTerms());
            response.put("update_in_progress", status.updateInProgress());
            json(ctx, 200, response);
        });
    }

    private static void throwBookNotReadyError(Javalin app) {
        app.exception(BookNotReadyException.class, (e, ctx) ->
                json(ctx, 409, Map.of(
                        "error", "book_not_ready",
                        "message", safeMessage(e)
                ))
        );
    }

    private static void throwInvalidBookIdError(Javalin app) {
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                json(ctx, 400, Map.of(
                        "error", "invalid_book_id",
                        "message", safeMessage(e)
                ))
        );
    }

    private static void throwIndexingIoError(Javalin app) {
        app.exception(IOException.class, (e, ctx) ->
                json(ctx, 500, Map.of(
                        "error", "indexing_io_error",
                        "message", safeMessage(e)
                ))
        );
    }

    private static void throwInternalError(Javalin app) {
        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            json(ctx, 500, Map.of("error", "internal_error"));
        });
    }

    private static void json(Context ctx, int status, Object value) {
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
        throw new IllegalArgumentException("book_id must be a positive 32-bit integer");
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int positiveIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= 65535) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(name + " must be a port between 1 and 65535");
    }

    public static void main(String[] args) throws IOException {
        int port = positiveIntEnv("INDEXING_PORT", 7002);
        Path datalake = Path.of(System.getenv().getOrDefault("DATALAKE_PATH", "book_datalake"));
        Path datamart = Path.of(System.getenv().getOrDefault("DATAMART_PATH", "book_datamart"));

        IndexingService service = new IndexingService(datalake, datamart);
        new App(service).createServer().start(port);
    }
}
