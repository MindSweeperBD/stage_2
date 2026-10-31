package com.mindsweeper.indexing;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class TextNormalizer {
    private static final String IS_UNICODE_OR_NUMBER = "[^\\p{L}\\p{N}]+";

    private TextNormalizer() {
    }

    public static Set<String> getWordsList(String text) {
        Set<String> words = new TreeSet<>();
        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replaceAll(IS_UNICODE_OR_NUMBER, " ");

        for (String word : normalized.split("\\s+")) {
            if (word.length() > 1) {
                words.add(word);
            }
        }
        return words;
    }
}
