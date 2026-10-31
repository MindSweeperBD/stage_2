package com.mindsweeper.search;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class App {

    private static final Gson JSON = new Gson();

    private final DatamartStore store;
    private final SearchEngine engine;

    public App(DatamartStore store, SearchEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    public Javalin createServer() {

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.bundledPlugins.enableCors(cors ->
                    cors.addRule(it -> it.anyHost())
            );
        });

        app.get("/status", ctx -> {
            DatamartStore.StoreStats stats = store.stats();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("service", "search");
            payload.put("status", "running");
            payload.put("indexed_books", stats.indexed_books());
            payload.put("indexed_terms", stats.indexed_terms());
            payload.put("metadata_db_available", stats.metadata_db_available());
            payload.put("inverted_index_available", stats.inverted_index_available());
            json(ctx, 200, payload);
        });

        app.get("/search", ctx -> {
            String q = ctx.queryParam("q");
            String author = ctx.queryParam("author");
            String language = ctx.queryParam("language");
            Integer year = parseOptionalInt(ctx.queryParam("year"), "year");

            int offset = parseOptionalIntOrDefault(
                    ctx.queryParam("offset"),
                    "offset",
                    SearchEngine.DEFAULT_OFFSET
            );
            int limit = parseOptionalIntOrDefault(
                    ctx.queryParam("limit"),
                    "limit",
                    SearchEngine.DEFAULT_LIMIT
            );

            SearchEngine.SearchQuery query = new SearchEngine.SearchQuery(
                    q, author, language, year, offset, limit
            );

            SearchEngine.SearchResponse response = engine.search(query);
            json(ctx, 200, response);
        });

        app.get("/books/{book_id}", ctx -> {
            int bookId = parsePositiveId(ctx.pathParam("book_id"), "book_id");
            Optional<DatamartStore.BookRecord> book = store.getBookById(bookId);

            if (book.isEmpty()) {
                json(ctx, 404, Map.of(
                        "error", "book_not_found",
                        "book_id", bookId
                ));
                return;
            }

            json(ctx, 200, book.get());
        });

        app.get("/search/filters", ctx -> {
            DatamartStore.FilterOptions filters = store.getAvailableFilters();
            json(ctx, 200, filters);
        });

        app.exception(IllegalArgumentException.class,
                (e, ctx) -> json(ctx, 400, Map.of(
                        "error", "invalid_parameter",
                        "message", e.getMessage()
                ))
        );

        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            json(ctx, 500, Map.of(
                    "error", "internal_error",
                    "message", e.getMessage() != null ? e.getMessage() : "Unexpected error"
            ));
        });

        return app;
    }

    private static void json(Context ctx, int status, Object value) {
        ctx.status(status)
                .contentType("application/json")
                .result(JSON.toJson(value));
    }

    private static int parsePositiveId(String value, String paramName) {
        try {
            int id = Integer.parseInt(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(
                paramName + " must be a positive 32-bit integer"
        );
    }

    private static Integer parseOptionalInt(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Parameter '" + paramName + "' must be a valid integer"
            );
        }
    }

    private static int parseOptionalIntOrDefault(
            String value,
            String paramName,
            int defaultValue
    ) {
        Integer parsed = parseOptionalInt(value, paramName);
        return parsed != null ? parsed : defaultValue;
    }

    public static void main(String[] args) {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("SEARCH_PORT", "7003")
        );

        Path datamartRoot = Path.of(
                System.getenv().getOrDefault("DATAMART_PATH", "datamart")
        );

        Path metadataDbPath = Path.of(
                System.getenv().getOrDefault(
                        "METADATA_DB_PATH",
                        datamartRoot.resolve("metadata.db").toString()
                )
        );

        Path invertedIndexPath = Path.of(
                System.getenv().getOrDefault(
                        "INVERTED_INDEX_PATH",
                        datamartRoot.resolve("inverted_index.json").toString()
                )
        );

        DatamartStore store = new DatamartStore(metadataDbPath, invertedIndexPath);
        SearchEngine engine = new SearchEngine(store);

        new App(store, engine).createServer().start(port);
    }
}