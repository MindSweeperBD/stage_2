package com.mindsweeper.indexing;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class InvertedIndexBuilder {
    private final Map<String, Set<Integer>> index = new TreeMap<>();

    public void load(Map<String, Set<Integer>> existingIndex) {
        index.clear();
        existingIndex.forEach((term, bookIds) ->
                index.put(term, new TreeSet<>(bookIds)));
    }

    public void processBook(BookMetadata metadata, Set<String> words) {
        for (String word : words) {
            index.computeIfAbsent(word, ignored -> new TreeSet<>())
                    .add(metadata.bookId());
        }
    }

    public void removeBook(int bookId) {
        var iterator = index.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<Integer>> entry = iterator.next();
            entry.getValue().remove(bookId);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    public Map<String, Set<Integer>> indexView() {
        Map<String, Set<Integer>> copy = new TreeMap<>();
        index.forEach((term, ids) -> copy.put(term, new TreeSet<>(ids)));
        return copy;
    }
}
