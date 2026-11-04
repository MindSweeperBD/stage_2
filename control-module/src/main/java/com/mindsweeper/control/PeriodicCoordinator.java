package com.mindsweeper.control;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Processes persisted PENDING books in batches, either periodically or on demand. */
public class PeriodicCoordinator implements AutoCloseable {

    private final PipelineCoordinator pipelineCoordinator;
    private final StateRepository stateRepository;
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    private final boolean enabled;
    private final AtomicBoolean batchRunning = new AtomicBoolean(false);

    private volatile long runs;
    private volatile long lastDurationMs;
    private volatile String lastRunAt;
    private volatile int lastProcessed;
    private volatile int lastFailed;
    private volatile String lastError;

    public PeriodicCoordinator(PipelineCoordinator pipelineCoordinator,
                               StateRepository stateRepository,
                               long intervalSeconds,
                               boolean enabled) {
        if (intervalSeconds < 1) {
            throw new IllegalArgumentException("Periodic interval must be at least 1 second");
        }
        this.pipelineCoordinator = pipelineCoordinator;
        this.stateRepository = stateRepository;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mindsweeper-periodic-coordinator");
            thread.setDaemon(true);
            return thread;
        });
        if (enabled) {
            scheduler.scheduleWithFixedDelay(this::scheduledRun,
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    public Map<String, Object> runBatch() throws IOException {
        if (!batchRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("A periodic batch is already running");
        }

        long start = System.nanoTime();
        int processed = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        lastRunAt = Instant.now().toString();
        lastError = null;

        try {
            List<Integer> pendingIds = new ArrayList<>();
            for (Map.Entry<Integer, StateRepository.PipelineRecord> entry
                    : stateRepository.getAllRecords().entrySet()) {
                if (entry.getValue().getState() == PipelineState.PENDING) {
                    pendingIds.add(entry.getKey());
                }
            }

            for (int bookId : pendingIds) {
                try {
                    PipelineState state = pipelineCoordinator.processBook(bookId);
                    processed++;
                    results.add(result(bookId, state.name(), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastError = "Batch interrupted";
                    results.add(result(bookId, "FAILED", lastError));
                    failed++;
                    break;
                } catch (IOException | RuntimeException e) {
                    failed++;
                    results.add(result(bookId, "FAILED", e.getMessage()));
                }
            }

            runs++;
            lastProcessed = processed;
            lastFailed = failed;
            lastDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("started_at", lastRunAt);
            response.put("pending_at_start", pendingIds.size());
            response.put("processed", processed);
            response.put("failed", failed);
            response.put("duration_ms", lastDurationMs);
            response.put("results", results);
            return response;
        } finally {
            batchRunning.set(false);
        }
    }

    private Map<String, Object> result(int bookId, String state, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("book_id", bookId);
        item.put("state", state);
        if (error != null) item.put("error", error);
        return item;
    }

    private void scheduledRun() {
        try {
            runBatch();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("[PERIODIC] Batch failed: " + lastError);
        }
    }

    public Map<String, Object> status() {
        int pending = 0;
        int failed = 0;
        for (StateRepository.PipelineRecord record : stateRepository.getAllRecords().values()) {
            if (record.getState() == PipelineState.PENDING) pending++;
            if (record.getState() == PipelineState.FAILED) failed++;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled && !scheduler.isShutdown());
        status.put("interval_seconds", intervalSeconds);
        status.put("batch_running", batchRunning.get());
        status.put("pending_books", pending);
        status.put("failed_books", failed);
        status.put("runs", runs);
        status.put("last_run_at", lastRunAt);
        status.put("last_processed", lastProcessed);
        status.put("last_failed", lastFailed);
        status.put("last_duration_ms", lastDurationMs);
        status.put("last_error", lastError);
        return status;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
