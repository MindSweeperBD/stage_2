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
    }
}
