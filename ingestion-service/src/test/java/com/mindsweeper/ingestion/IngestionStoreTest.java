package com.mindsweeper.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IngestionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesBookCorrectly() throws IOException {

        IngestionStore store = new IngestionStore(tempDir);

        BookDownloader.Book book =
                new BookDownloader.Book(
                        "Title: Test Book\nAuthor: Test",
                        "This is the body of the book."
                );

        store.save(100, book);

        assertTrue(store.isComplete(100));
        assertTrue(Files.exists(
                tempDir.resolve("100/header.txt")));
        assertTrue(Files.exists(
                tempDir.resolve("100/body.txt")));
        assertTrue(Files.exists(
                tempDir.resolve("100/.complete")));
    }

    @Test
    void avoidsDuplicateBooks() throws IOException {

        IngestionStore store = new IngestionStore(tempDir);

        BookDownloader.Book book =
                new BookDownloader.Book("Header", "Body");

        store.save(100, book);
        store.save(100, book);

        assertEquals(List.of(100), store.list());
    }

    @Test
    void incompleteBookIsNotAvailable() throws IOException {

        IngestionStore store = new IngestionStore(tempDir);

        Path folder = tempDir.resolve("200");
        Files.createDirectories(folder);
        Files.writeString(
                folder.resolve("header.txt"), "Header");

        assertFalse(store.isComplete(200));
        assertFalse(store.list().contains(200));
    }

    @Test
    void listsOnlyCompleteBooks() throws IOException {

        IngestionStore store = new IngestionStore(tempDir);

        store.save(20,
                new BookDownloader.Book("Header", "Body 20"));

        store.save(10,
                new BookDownloader.Book("Header", "Body 10"));

        assertEquals(List.of(10, 20), store.list());
    }

    @Test
    void splitsGutenbergText() throws IOException {

        String text = """
                Title: Example Book
                Author: Example Author

                *** START OF THE PROJECT GUTENBERG EBOOK EXAMPLE ***

                This is a test book.

                *** END OF THE PROJECT GUTENBERG EBOOK EXAMPLE ***
                """;

        BookDownloader.Book book = BookDownloader.split(text);

        assertTrue(book.header().contains("Example Author"));
        assertTrue(book.body().contains("This is a test book."));
    }

    @Test
    void rejectsInvalidGutenbergText() {

        assertThrows(IOException.class,
                () -> BookDownloader.split(
                        "This text has no Gutenberg markers"));
    }
}
