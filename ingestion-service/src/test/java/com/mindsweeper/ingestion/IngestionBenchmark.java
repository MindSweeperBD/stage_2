package com.mindsweeper.ingestion;

import org.openjdk.jmh.annotations.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class IngestionBenchmark {

    private String sample;
    private IngestionStore store;
    private int id;

    @Setup(Level.Trial)
    public void setup() throws Exception {

        sample =
                "Title: Benchmark\n" +
                "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n" +
                "a small book paragraph repeated for testing.\n"
                        .repeat(100) +
                "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***";

        store = new IngestionStore(
                Files.createTempDirectory("mindsweeper-jmh-")
        );
    }

    @Setup(Level.Invocation)
    public void next() {
        id++;
    }

    @Benchmark
    public BookDownloader.Book splitText()
            throws Exception {

        return BookDownloader.split(sample);
    }

    @Benchmark
    public Path saveBook() throws Exception {

        BookDownloader.Book book =
                new BookDownloader.Book(
                        "Title: Benchmark",
                        "content ".repeat(500)
                );

        return store.save(id, book);
    }
}
