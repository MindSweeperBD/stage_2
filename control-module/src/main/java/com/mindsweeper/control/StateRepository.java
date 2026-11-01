package com.mindsweeper.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class StateRepository {

    private final Path filePath;

    private final Gson gson;

    private final Map<Integer, PipelineRecord> records;

    public StateRepository() throws IOException {

        String path = System.getenv()
                .getOrDefault("CONTROL_STATE_PATH", "data/pipeline-state.json");

        this.filePath = Path.of(path);

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();

        this.records = new LinkedHashMap<>();

        load();
    }

    private void load() throws IOException {

        if (!Files.exists(filePath)) {

            Path parent = filePath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            save();
            return;
        }

        String content = Files.readString(
                filePath,
                StandardCharsets.UTF_8
        );

        if (!content.isBlank()) {

            Type type = new TypeToken<Map<String, PipelineRecord>>() {
            }.getType();

            Map<String, PipelineRecord> loaded = gson.fromJson(content, type);

            if (loaded != null) {
                for (Map.Entry<String, PipelineRecord> entry : loaded.entrySet()) {
                    records.put(
                            Integer.parseInt(entry.getKey()),
                            entry.getValue()
                    );
                }
            }
        }
    }

    public synchronized void updateState(
            int bookId,
            PipelineState state,
            String error
    ) throws IOException {

        if (bookId <= 0) {
            throw new IllegalArgumentException("Book ID must be positive");
        }

        PipelineRecord record = records.get(bookId);

        if (record == null) {
            record = new PipelineRecord();
            records.put(bookId, record);
        }

        record.state = state;

        if (error != null) {
            record.lastError = error;
        } else {
            record.lastError = null;
        }

        save();
    }

    public synchronized void incrementAttempts(int bookId)
            throws IOException {

        PipelineRecord record = records.get(bookId);

        if (record == null) {
            record = new PipelineRecord();
            record.state = PipelineState.PENDING;
            records.put(bookId, record);
        }

        record.attempts++;

        save();
    }

    public synchronized PipelineRecord getRecord(int bookId) {
        return records.get(bookId);
    }

    public synchronized Map<Integer, PipelineRecord> getAllRecords() {
        return new LinkedHashMap<>(records);
    }

    private void save() throws IOException {

        String json = gson.toJson(records);

        Files.writeString(
                filePath,
                json,
                StandardCharsets.UTF_8
        );
    }

    public static class PipelineRecord {

        private PipelineState state = PipelineState.PENDING;

        private int attempts = 0;

        private String lastError;

        public PipelineState getState() {
            return state;
        }

        public int getAttempts() {
            return attempts;
        }

        public String getLastError() {
            return lastError;
        }
    }
}
