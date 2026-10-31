package com.mindsweeper.indexing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class InvertedIndexRepository {
    private static final Type JSON_TYPE = new TypeToken<TreeMap<String, List<Integer>>>() { }.getType();
    private final Path indexPath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public InvertedIndexRepository(Path indexPath) {
        this.indexPath = indexPath.toAbsolutePath().normalize();
    }

    public Path indexPath() {
        return indexPath;
    }

    public Map<String, Set<Integer>> load() throws IOException {
        Map<String, Set<Integer>> result = new TreeMap<>();
        if (!Files.isRegularFile(indexPath)) {
            return result;
        }

        TreeMap<String, List<Integer>> serialized = gson.fromJson(
                Files.readString(indexPath, StandardCharsets.UTF_8), JSON_TYPE);
        if (serialized == null) {
            throw new IOException("Inverted index JSON is empty or invalid: " + indexPath);
        }

        for (Map.Entry<String, List<Integer>> entry : serialized.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IOException("Invalid term or postings in " + indexPath);
            }
            Set<Integer> ids = new TreeSet<>();
            for (Integer id : entry.getValue()) {
                if (id == null || id <= 0) {
                    throw new IOException("Invalid book ID in postings for term: " + entry.getKey());
                }
                ids.add(id);
            }
            if (!ids.isEmpty()) {
                result.put(entry.getKey(), ids);
            }
        }
        return result;
    }

    String toJson(Map<String, Set<Integer>> index) {
        TreeMap<String, List<Integer>> ordered = new TreeMap<>();
        index.forEach((term, ids) -> {
            List<Integer> sortedIds = new ArrayList<>(ids);
            sortedIds.sort(Integer::compareTo);
            if (!sortedIds.isEmpty()) {
                ordered.put(term, sortedIds);
            }
        });
        return gson.toJson(ordered);
    }
}
