package com.mindsweeper.indexing;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class TextNormalizer {
    private static final String IS_NOT_UNICODE_OR_NUMBER = "[^\\p{L}\\p{N}]+";

    private TextNormalizer() {
    }

    public static Set<String> getWordsList(String text) {
        Set<String> words = new TreeSet<>();

        String normalized = text.toLowerCase(Locale.ROOT);

        for (String word : normalized.split(IS_NOT_UNICODE_OR_NUMBER)) {
            if (word.length() > 1) {
                words.add(word);
            }
        }
        return words;
    }
}
