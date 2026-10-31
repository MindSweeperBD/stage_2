package com.mindsweeper.indexing;

public record BookMetadata(
        int bookId,
        String title,
        String author,
        String language,
        Integer year,
        String bodyPath
) {
}
