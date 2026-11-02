package com.mindsweeper.control;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class PipelineCoordinator {

    private final ServiceClient serviceClient;
    private final StateRepository stateRepository;

    private final ConcurrentHashMap<Integer, ReentrantLock> bookLocks =
            new ConcurrentHashMap<>();

    public PipelineCoordinator(ServiceClient serviceClient,
                               StateRepository stateRepository) {
        this.serviceClient = serviceClient;
        this.stateRepository = stateRepository;
    }

    private ReentrantLock getLock(int bookId) {
        return bookLocks.computeIfAbsent(
                bookId,
                id -> new ReentrantLock()
        );
    }

    public PipelineState processBook(int bookId)
            throws IOException, InterruptedException {

        if (bookId <= 0) {
            throw new IllegalArgumentException("Invalid book ID");
        }

        long requestTime = System.currentTimeMillis();

        System.out.println(
                "[CONCURRENCY] Book " + bookId +
                        " - REQUESTED at " + requestTime
        );

        ReentrantLock lock = getLock(bookId);

        lock.lockInterruptibly();

        long startTime = System.currentTimeMillis();

        System.out.println(
                "[CONCURRENCY] Book " + bookId +
                        " - STARTED at " + startTime
        );

        try {
            if (getBookState(bookId) == PipelineState.VERIFIED) {
                return PipelineState.VERIFIED;
            }

            stateRepository.incrementAttempts(bookId);

            try {
                stateRepository.updateState(
                        bookId,
                        PipelineState.INGESTING,
                        null
                );

                serviceClient.ingestBook(bookId);

                if (!serviceClient.isBookIngested(bookId)) {
                    throw new IOException("Book ingestion not completed");
                }

                stateRepository.updateState(
                        bookId,
                        PipelineState.INGESTED,
                        null
                );

                stateRepository.updateState(
                        bookId,
                        PipelineState.INDEXING,
                        null
                );

                serviceClient.indexBook(bookId);

                stateRepository.updateState(
                        bookId,
                        PipelineState.INDEXED,
                        null
                );

                if (!serviceClient.isBookIndexed(bookId)) {
                    throw new IOException(
                            "Book was not found in Search Service"
                    );
                }

                stateRepository.updateState(
                        bookId,
                        PipelineState.VERIFIED,
                        null
                );

                return PipelineState.VERIFIED;

            } catch (InterruptedException e) {
                stateRepository.updateState(
                        bookId,
                        PipelineState.FAILED,
                        "Processing interrupted"
                );

                Thread.currentThread().interrupt();
                throw e;

            } catch (IOException | RuntimeException e) {
                stateRepository.updateState(
                        bookId,
                        PipelineState.FAILED,
                        e.getMessage()
                );

                throw e;
            }

        } finally {
            long endTime = System.currentTimeMillis();

            System.out.println(
                    "[CONCURRENCY] Book " + bookId +
                            " - FINISHED at " + endTime +
                            " - Duration: " + (endTime - startTime) + " ms"
            );

            lock.unlock();
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