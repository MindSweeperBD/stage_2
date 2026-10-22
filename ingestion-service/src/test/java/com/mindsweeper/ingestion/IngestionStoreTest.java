package com.mindsweeper.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IngestionStoreTest {

    @TempDir
    Path temp;

    private static final BookDownloader.Book BOOK =
            new BookDownloader.Book(
                    "Title: Example",
                    "The body of the book."
            );

    @Test
    void savesAndLists() throws Exception {

        IngestionStore store = new IngestionStore(temp);

        store.save(20, BOOK);
        store.save(10, BOOK);

        assertEquals(List.of(10, 20), store.list());

        assertTrue(
                Files.readString(
                        temp.resolve("10/body.txt")
                ).contains("body")
        );

        assertEquals(6, store.stats().files());
        assertEquals(3, store.stats().directories());
        assertTrue(store.stats().bytes() > 0);
    }

    @Test
    void repeatedUpdateIsIdempotent() throws Exception {

        IngestionStore store = new IngestionStore(temp);

        store.save(7, BOOK);

        store.save(
                7,
                new BookDownloader.Book(
                        "changed",
                        "changed"
                )
        );

        assertEquals(
                "The body of the book.",
                Files.readString(
                        temp.resolve("7/body.txt")
                )
        );

        assertEquals(1, store.list().size());
    }

    @Test
    void missingMarkerOrBodyIsIncomplete()
            throws Exception {

        IngestionStore store = new IngestionStore(temp);

        Path directory = store.bookDir(3);

        Files.createDirectories(directory);

        Files.writeString(
                directory.resolve("header.txt"),
                "header"
        );

        assertFalse(store.isComplete(3));

        Files.writeString(
                directory.resolve("body.txt"),
                "body"
        );

        assertFalse(store.isComplete(3));

        Files.writeString(
                directory.resolve(".complete"),
                "complete"
        );

        assertTrue(store.isComplete(3));
    }

    @Test
    void splitRemovesWholeMarkerLine()
            throws Exception {

        String input =
                "Title: Test\n" +
                "Author: Example\n" +
                "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n" +
                "Hello world\n" +
                "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\n" +
                "footer";

        BookDownloader.Book book =
                BookDownloader.split(input);

        assertEquals("Hello world", book.body());

        assertTrue(
                book.header().contains("Author: Example")
        );

        assertThrows(
                IOException.class,
                () -> BookDownloader.split("bad data")
        );

        String emptyBook =
                "*** START OF THE PROJECT GUTENBERG EBOOK X ***\n" +
                "\n" +
                "*** END OF THE PROJECT GUTENBERG EBOOK X ***";

        assertThrows(
                IOException.class,
                () -> BookDownloader.split(emptyBook)
        );
    }

    @Test
    void interruptedWriteIsNotVisibleAndCanRecover()
            throws Exception {

        IngestionStore store = new IngestionStore(temp);

        Path directory = store.bookDir(99);

        Files.createDirectories(directory);

        Files.writeString(
                directory.resolve("header.txt"),
                "incomplete"
        );

        Files.writeString(
                directory.resolve("body-123.tmp"),
                "partial"
        );

        assertFalse(store.isComplete(99));

        store.save(99, BOOK);

        assertTrue(store.isComplete(99));
        assertEquals(List.of(99), store.list());
    }

    @Test
    void parallelSavesDoNotDuplicate()
            throws Exception {

        IngestionStore store = new IngestionStore(temp);

        var threads = new java.util.ArrayList<Thread>();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < 8; i++) {

            Thread thread = new Thread(() -> {

                try {
                    store.save(8, BOOK);
                } catch (Throwable e) {
                    failures.add(e);
                }
            });

            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(List.of(8), store.list());
        assertEquals(3, store.stats().files());
    }
}
