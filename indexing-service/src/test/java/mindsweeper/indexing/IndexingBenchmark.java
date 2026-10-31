package mindsweeper.indexing;

import mindsweeper.IndexingService;
import mindsweeper.MetadataExtractor;
import mindsweeper.TextNormalizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final String SAMPLE_TEXT = ("The quick brown fox jumps over the lazy dog. "
            + "Indexing services transform documents into searchable terms. ").repeat(250);

    @State(Scope.Benchmark)
    public static class SharedIndexState {
        Path work;
        Path datalake;
        Path datamart;
        IndexingService service;
        AtomicInteger nextId;
        AtomicLong revision;
        ConcurrentHashMap<Long, Integer> idsByThread;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            work = Files.createTempDirectory("mindsweeper-jmh-");
            datalake = work.resolve("book_datalake");
            datamart = work.resolve("book_datamarts");
            Files.createDirectories(datalake);
            service = new IndexingService(datalake, datamart);
            nextId = new AtomicInteger(20000);
            revision = new AtomicLong();
            idsByThread = new ConcurrentHashMap<>();
        }

        @Setup(Level.Invocation)
        public void prepareBookForCurrentWorker() throws IOException {
            long threadId = Thread.currentThread().getId();
            int id = idsByThread.computeIfAbsent(threadId, ignored -> nextId.getAndIncrement());
            long currentRevision = revision.incrementAndGet();
            Path directory = datalake.resolve(Integer.toString(id));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("header.txt"), HEADER, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("body.txt"),
                    SAMPLE_TEXT + " revisiontoken" + String.format(java.util.Locale.ROOT, "%08d", currentRevision), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(".complete"), "complete\n", StandardCharsets.UTF_8);
        }

        @TearDown(Level.Trial)
        public void cleanup() throws IOException {
            if (work != null && Files.exists(work)) {
                try (var paths = Files.walk(work)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
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
    public IndexingService.UpdateResult updateBook(SharedIndexState state) throws IOException {
        int id = state.idsByThread.get(Thread.currentThread().getId());
        return state.service.updateBook(id);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public IndexingService.UpdateResult updateBookThroughput(SharedIndexState state)
            throws IOException {
        int id = state.idsByThread.get(Thread.currentThread().getId());
        return state.service.updateBook(id);
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
