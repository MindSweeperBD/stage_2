package com.mindsweeper.indexing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataExtractorTest {
    private final MetadataExtractor extractor = new MetadataExtractor();

    @Test
    void extractsTheThreeMetadataFieldsUsedInStageOne() {
        String header = """
                The Project Gutenberg eBook of Pride and Prejudice
                
                Title: Pride and Prejudice
                
                Author: Jane Austen
                
                Release date: May 1, 1998 [eBook #1342]
                
                Language: English
                """;
        BookMetadata metadata = extractor.extract(1342, header, "book_datalake/1342/body.txt");
        assertEquals(1342, metadata.bookId());
        assertEquals("Pride and Prejudice", metadata.title());
        assertEquals("Jane Austen", metadata.author());
        assertEquals("English", metadata.language());
        assertEquals(1998, metadata.year());
        assertEquals("book_datalake/1342/body.txt", metadata.bodyPath());
    }

    @Test
    void missingFieldsAreStoredAsEmptyStringsLikeStageOne() {
        BookMetadata metadata = extractor.extract(123, "No Gutenberg metadata here", "body.txt");
        assertEquals("", metadata.title());
        assertEquals("", metadata.author());
        assertEquals("", metadata.language());
        assertNull(metadata.year());
    }
}
