package com.mindsweeper.indexing;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IndexingBenchmark {

    private static final String HEADER = """
            Title: Benchmark Book
            Author: JMH Test Author
            Language: English
            Year: 1996
            """;

    private static final String SAMPLE_TEXT = (
            "The quick brown fox jumps over the lazy dog. "
            + "Indexing services transform documents into searchable terms. "
    ).repeat(250);

    @State(Scope.Benchmark)
    public static class SharedIndexState {
        Path work;
        Path datalake;
        Path datamart;
        IndexingService service;

        AtomicInteger nextId;
        AtomicLong revision;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            work = Files.createTempDirectory("mindsweeper-jmh-");

            datalake = work.resolve("book_datalake");
            datamart = work.resolve("book_datamart");

            Files.createDirectories(datalake);

            service = new IndexingService(datalake, datamart);

            nextId = new AtomicInteger(20000);
            revision = new AtomicLong();
        }

        @TearDown(Level.Trial)
        public void cleanup() throws IOException {
            if (work != null && Files.exists(work)) {
                try (var paths = Files.walk(work)) {
                    for (Path path : paths
                            .sorted(Comparator.reverseOrder())
                            .toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @State(Scope.Thread)
    public static class ThreadBookState {
        int bookId;

        @Setup(Level.Invocation)
        public void prepareBook(SharedIndexState shared) throws IOException {

            bookId = shared.nextId.getAndIncrement();
            long currentRevision = shared.revision.incrementAndGet();

            Path directory = shared.datalake.resolve(
                    Integer.toString(bookId)
            );
            Files.createDirectories(directory);

            Files.writeString(
                    directory.resolve("header.txt"),
                    HEADER,
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    directory.resolve("body.txt"),
                    SAMPLE_TEXT
                            + " revisiontoken"
                            + String.format(
                                    java.util.Locale.ROOT,
                                    "%08d",
                                    currentRevision
                            ),
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    directory.resolve(".complete"),
                    "complete\n",
                    StandardCharsets.UTF_8
            );
        }
    }

    @State(Scope.Benchmark)
    public static class TextState {
        String text;
        String header;
        MetadataExtractor extractor;

        @Setup(Level.Trial)
        public void setup() {
            text = SAMPLE_TEXT;
            header = HEADER;
            extractor = new MetadataExtractor();
        }
    }

    @Benchmark
    public void updateBook(SharedIndexState shared, ThreadBookState thread) throws IOException {
        shared.service.updateBook(thread.bookId);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void updateBookThroughput(SharedIndexState shared, ThreadBookState thread) throws IOException {
        shared.service.updateBook(thread.bookId);
    }

    @Benchmark
    public Set<String> normalizeText(TextState state) {
        return TextNormalizer.getWordsList(state.text);
    }

    @Benchmark
    public Object extractMetadata(TextState state) {
        return state.extractor.extract(1342, state.header, "book_datalake/1342/body.txt");
    }
}
