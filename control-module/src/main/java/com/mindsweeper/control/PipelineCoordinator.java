package com.mindsweeper.control;

import java.io.IOException;

public class PipelineCoordinator {

    private final ServiceClient serviceClient;

    private final StateRepository stateRepository;

    public PipelineCoordinator(
            ServiceClient serviceClient,
            StateRepository stateRepository
    ) {
        this.serviceClient = serviceClient;
        this.stateRepository = stateRepository;
    }

    public void processBook(int bookId)
            throws IOException, InterruptedException {

        if (bookId <= 0) {
            throw new IllegalArgumentException("Book ID must be positive");
        }

        StateRepository.PipelineRecord record =
                stateRepository.getRecord(bookId);

        if (record != null
                && record.getState() == PipelineState.VERIFIED) {
            return;
        }

        stateRepository.incrementAttempts(bookId);

        try {

            // Step 1: Ingestion
            stateRepository.updateState(
                    bookId, PipelineState.INGESTING, null
            );

            serviceClient.ingestBook(bookId);

            if (!serviceClient.isBookIngested(bookId)) {
                throw new IOException("Book was not successfully ingested");
            }

            stateRepository.updateState(
                    bookId, PipelineState.INGESTED, null
            );

            // Step 2: Indexing
            stateRepository.updateState(
                    bookId, PipelineState.INDEXING, null
            );

            serviceClient.indexBook(bookId);

            stateRepository.updateState(
                    bookId, PipelineState.INDEXED, null
            );

            // Step 3: Search verification
            if (!serviceClient.isBookIndexed(bookId)) {
                throw new IOException("Book was not found in Search Service");
            }

            stateRepository.updateState(
                    bookId, PipelineState.VERIFIED, null
            );

        } catch (InterruptedException e) {

            stateRepository.updateState(
                    bookId, PipelineState.FAILED, "Process interrupted"
            );

            Thread.currentThread().interrupt();
            throw e;

        } catch (IOException | RuntimeException e) {

            stateRepository.updateState(
                    bookId, PipelineState.FAILED, e.getMessage()
            );

            throw e;
        }
    }

    public PipelineState getBookState(int bookId) {

        StateRepository.PipelineRecord record =
                stateRepository.getRecord(bookId);

        if (record == null) {
            return PipelineState.PENDING;
        }

        return record.getState();
    }
}
