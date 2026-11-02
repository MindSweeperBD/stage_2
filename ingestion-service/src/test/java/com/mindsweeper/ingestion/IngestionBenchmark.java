
package com.mindsweeper.ingestion;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
@Threads(1)
public class IngestionBenchmark {

    private String sample;
    private BookDownloader.Book book;
    private IngestionStore store;
    private Path root;
    private int id;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        sample = "Title: Benchmark\n" +
                "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n" +
                "a small book paragraph repeated for testing.\n".repeat(100) +
                "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***";

        book = new BookDownloader.Book(
                "Title: Benchmark",
                "content ".repeat(500)
        );

        root = Files.createTempDirectory("mindsweeper-jmh-");
        store = new IngestionStore(root);
        id = 0;
    }

    @Setup(Level.Invocation)
    public void next() {
        id++;
    }

    @Benchmark
    public BookDownloader.Book splitText() throws IOException {
        return BookDownloader.split(sample);
    }

    @Benchmark
    public Path saveBook() throws IOException {
        return store.save(id, book);
    }

    @TearDown(Level.Invocation)
    public void cleanBook() throws IOException {
        if (id > 0) {
            deleteTree(root.resolve(Integer.toString(id)));
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        deleteTree(root);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : paths
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
