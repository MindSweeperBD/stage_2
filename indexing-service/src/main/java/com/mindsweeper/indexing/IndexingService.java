package com.mindsweeper.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class IndexingService {
    private final DatalakeReader datalake;
    private final DatamartRepository repository;
    private final MetadataExtractor metadataExtractor;
    private final ReentrantLock updateLock = new ReentrantLock(true);
    private final AtomicInteger activeMutations = new AtomicInteger();
    private final AtomicReference<IndexState> current = new AtomicReference<>(IndexState.empty());

    public IndexingService(java.nio.file.Path datalakeRoot,
                           java.nio.file.Path datamartRoot) throws IOException {
        this(datalakeRoot, datamartRoot, new MetadataExtractor());
    }

    IndexingService(java.nio.file.Path datalakeRoot,
                    java.nio.file.Path datamartRoot,
                    MetadataExtractor metadataExtractor) throws IOException {
        this.datalake = new DatalakeReader(datalakeRoot);
        this.metadataExtractor = metadataExtractor;
        try {
            this.repository = new DatamartRepository(datamartRoot);
            loadPublishedState();
        } catch (java.sql.SQLException e) {
            throw new IOException("Could not initialize metadata.db", e);
        }
    }

    public UpdateResult updateBook(int bookId) throws IOException {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be a positive 32-bit integer");
        }

        activeMutations.incrementAndGet();
        updateLock.lock();
        try (DatamartRepository.AccessLock ignored = repository.acquireWriteLock()) {
            if (repository.publicationPending()) {
                throw new IOException("A previous datamart publication was interrupted; run /index/rebuild before updating individual books");
            }

            DatalakeReader.BookDocument source = datalake.readReadyBook(bookId);
            BookMetadata metadata = metadataExtractor.extract(
                    bookId, source.header(), source.bodyPath());
            Set<String> words = TextNormalizer.getWordsList(source.body());

            Map<Integer, BookMetadata> books = new TreeMap<>(repository.loadMetadata());
            Map<String, Set<Integer>> existingPostings = repository.loadInvertedIndex();

            BookMetadata existing = books.get(bookId);
            if (metadata.equals(existing) && wordsForBook(existingPostings, bookId).equals(words)) {
                IndexState state = createState(books, existingPostings, current.get().lastUpdate());
                current.set(state);
                return new UpdateResult(bookId, "already_indexed", state.version(),
                        state.lastUpdate(), words.size());
            }

            MonolithicInvertedIndexBuilder builder = new MonolithicInvertedIndexBuilder();
            builder.load(existingPostings);
            builder.removeBook(bookId);
            builder.processBook(metadata, words);
            books.put(bookId, metadata);
            Map<String, Set<Integer>> postings = builder.indexView();

            repository.publish(List.of(metadata), postings, false);
            String indexedAt = Instant.now().toString();
            IndexState next = createState(books, postings, indexedAt);
            current.set(next);

            return new UpdateResult(bookId, "updated", next.version(),
                    next.lastUpdate(), words.size());
        } catch (java.sql.SQLException e) {
            throw new IOException("Could not update metadata.db", e);
        } finally {
            updateLock.unlock();
            activeMutations.decrementAndGet();
        }
    }

    public RebuildResult rebuild() throws IOException {
        activeMutations.incrementAndGet();
        updateLock.lock();
        long started = System.nanoTime();
        try (DatamartRepository.AccessLock ignored = repository.acquireWriteLock()) {
            TreeMap<Integer, BookMetadata> books = new TreeMap<>();
            MonolithicInvertedIndexBuilder builder = new MonolithicInvertedIndexBuilder();
            int skippedIncomplete = 0;

            for (int id : datalake.listCandidateBookIds()) {
                DatalakeReader.BookDocument source;
                try {
                    source = datalake.readReadyBook(id);
                } catch (BookNotReadyException e) {
                    skippedIncomplete++;
                    continue;
                }

                BookMetadata metadata = metadataExtractor.extract(
                        id, source.header(), source.bodyPath());
                Set<String> words = TextNormalizer.getWordsList(source.body());
                books.put(id, metadata);
                builder.processBook(metadata, words);
            }

            Map<String, Set<Integer>> postings = builder.indexView();
            repository.publish(books.values(), postings, true);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String indexedAt = Instant.now().toString();
            IndexState next = createState(books, postings, indexedAt);
            current.set(next);

            return new RebuildResult(books.size(), skippedIncomplete, elapsedMs,
                    String.format(java.util.Locale.ROOT, "%.3fs", elapsedMs / 1000.0),
                    next.version());
        } finally {
            updateLock.unlock();
            activeMutations.decrementAndGet();
        }
    }

    public IndexStatus status() throws IOException {
        IndexState state = current.get();
        long sizeBytes = repository.totalSizeBytes();
        double sizeMb = sizeBytes / (1024.0 * 1024.0);
        return new IndexStatus(
                state.books().size(),
                state.lastUpdate(),
                sizeBytes,
                sizeMb,
                state.version(),
                state.invertedIndex().size(),
                activeMutations.get() > 0 || repository.publicationPending()
        );
    }

    public Map<Integer, BookMetadata> currentMetadata() {
        return current.get().books();
    }

    public Map<String, Set<Integer>> currentInvertedIndex() {
        return current.get().invertedIndex();
    }

    private void loadPublishedState() throws IOException {
        if (repository.publicationPending()) {
            current.set(IndexState.recoveryRequired());
            return;
        }
        try (DatamartRepository.AccessLock ignored = repository.acquireReadLock()) {
            Map<Integer, BookMetadata> books = repository.loadMetadata();
            Map<String, Set<Integer>> postings = repository.loadInvertedIndex();
            String lastUpdate = books.isEmpty() && postings.isEmpty()
                    ? null : repository.lastUpdateFromFiles();
            current.set(createState(books, postings, lastUpdate));
        } catch (java.sql.SQLException e) {
            throw new IOException("Could not load metadata.db", e);
        }
    }

    private static IndexState createState(Map<Integer, BookMetadata> books,
                                          Map<String, Set<Integer>> postings,
                                          String lastUpdate) {
        TreeMap<Integer, BookMetadata> booksCopy = new TreeMap<>(books);
        TreeMap<String, Set<Integer>> indexCopy = new TreeMap<>();
        postings.forEach((term, ids) ->
                indexCopy.put(term, Collections.unmodifiableSet(new TreeSet<>(ids))));
        Map<Integer, BookMetadata> immutableBooks = Collections.unmodifiableMap(booksCopy);
        Map<String, Set<Integer>> immutableIndex = Collections.unmodifiableMap(indexCopy);
        return new IndexState(versionOf(immutableBooks, immutableIndex), lastUpdate,
                immutableBooks, immutableIndex);
    }

    private static String versionOf(Map<Integer, BookMetadata> books,
                                    Map<String, Set<Integer>> index) {
        if (books.isEmpty() && index.isEmpty()) {
            return "none";
        }
        StringBuilder canonical = new StringBuilder();
        books.forEach((id, metadata) -> canonical.append(id).append('|')
                .append(metadata.title()).append('|')
                .append(metadata.author()).append('|')
                .append(metadata.language()).append('|')
                .append(metadata.bodyPath()).append('\n'));
        index.forEach((term, ids) -> {
            canonical.append(term).append(':');
            ids.forEach(id -> canonical.append(id).append(','));
            canonical.append('\n');
        });
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static Set<String> wordsForBook(Map<String, Set<Integer>> postings, int bookId) {
        Set<String> words = new TreeSet<>();
        postings.forEach((term, ids) -> {
            if (ids.contains(bookId)) {
                words.add(term);
            }
        });
        return words;
    }

    public record UpdateResult(int bookId, String index, String version,
                               String indexedAt, int uniqueTerms) {
    }

    public record RebuildResult(int booksProcessed, int skippedIncomplete,
                                long elapsedTimeMs, String elapsedTime,
                                String version) {
    }

    public record IndexStatus(long booksIndexed, String lastUpdate,
                              long indexSizeBytes, double indexSizeMB,
                              String version, long uniqueTerms,
                              boolean updateInProgress) {
    }

    private record IndexState(String version,
                              String lastUpdate,
                              Map<Integer, BookMetadata> books,
                              Map<String, Set<Integer>> invertedIndex) {
        static IndexState empty() {
            return new IndexState("none", null, Map.of(), Map.of());
        }

        static IndexState recoveryRequired() {
            return new IndexState("recovery-required", null, Map.of(), Map.of());
        }
    }
}
