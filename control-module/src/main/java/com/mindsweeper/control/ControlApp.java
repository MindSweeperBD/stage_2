package com.mindsweeper.control;

import io.javalin.Javalin;

import java.io.IOException;
import java.util.Map;

public class ControlApp {

    public static void main(String[] args) throws IOException {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("CONTROL_PORT", "7004")
        );

        ServiceClient serviceClient = new ServiceClient();

        StateRepository stateRepository = new StateRepository();

        PipelineCoordinator coordinator = new PipelineCoordinator(
                serviceClient,
                stateRepository
        );

        long periodicInterval = Long.parseLong(
                System.getenv().getOrDefault("PERIODIC_INTERVAL_SECONDS", "30")
        );
        boolean periodicEnabled = Boolean.parseBoolean(
                System.getenv().getOrDefault("PERIODIC_ENABLED", "true")
        );
        PeriodicCoordinator periodicCoordinator = new PeriodicCoordinator(
                coordinator, stateRepository, periodicInterval, periodicEnabled
        );

        Javalin app = Javalin.create(config ->
                config.jsonMapper(new GsonJsonMapper())
        );

        // Check service status
        app.get("/status", ctx -> {
            ctx.json(Map.of(
                    "service", "control",
                    "status", "running"
            ));
        });

        // Process a book
        app.post("/process/{book_id}", ctx -> {

            int bookId;

            try {
                bookId = Integer.parseInt(ctx.pathParam("book_id"));

                if (bookId <= 0) {
                    throw new NumberFormatException();
                }

            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of(
                        "error", "invalid_book_id"
                ));
                return;
            }

            try {

                coordinator.processBook(bookId);

                ctx.json(Map.of(
                        "book_id", bookId,
                        "state", coordinator.getBookState(bookId).name()
                ));

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                ctx.status(503);
                ctx.json(Map.of(
                        "book_id", bookId,
                        "state", "FAILED",
                        "error", "Process interrupted"
                ));

            } catch (IOException e) {

                ctx.status(502);
                ctx.json(Map.of(
                        "book_id", bookId,
                        "state", "FAILED",
                        "error", e.getMessage() == null
                                ? "Processing failed"
                                : e.getMessage()
                ));
            }
        });

        // Periodic strategy status
        app.get("/periodic/status", ctx -> ctx.json(periodicCoordinator.status()));

        // Add a book to the persistent pending queue for periodic processing.
        app.post("/periodic/queue/{book_id}", ctx -> {
            int bookId;
            try {
                bookId = Integer.parseInt(ctx.pathParam("book_id"));
                if (bookId <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of("error", "invalid_book_id"));
                return;
            }

            try {
                stateRepository.registerPending(bookId);
                PipelineState state = coordinator.getBookState(bookId);
                ctx.status(state == PipelineState.PENDING ? 202 : 200);
                ctx.json(Map.of(
                        "book_id", bookId,
                        "state", state.name(),
                        "queued", state == PipelineState.PENDING
                ));
            } catch (IOException e) {
                ctx.status(500);
                ctx.json(Map.of("error", "could_not_queue_book", "details",
                        e.getMessage() == null ? "Persistence failed" : e.getMessage()));
            }
        });

        // Execute the pending batch immediately (useful for tests and benchmarks).
        app.post("/periodic/run", ctx -> {
            try {
                ctx.json(periodicCoordinator.runBatch());
            } catch (IllegalStateException e) {
                ctx.status(409);
                ctx.json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(500);
                ctx.json(Map.of("error", "periodic_batch_failed"));
            }
        });

        // Get book processing state
        app.get("/state/{book_id}", ctx -> {

            int bookId;

            try {
                bookId = Integer.parseInt(ctx.pathParam("book_id"));

                if (bookId <= 0) {
                    throw new NumberFormatException();
                }

            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(Map.of(
                        "error", "invalid_book_id"
                ));
                return;
            }

            ctx.json(Map.of(
                    "book_id", bookId,
                    "state", coordinator.getBookState(bookId).name()
            ));
        });

        app.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            periodicCoordinator.close();
            app.stop();
        }, "control-shutdown"));
    }
}
