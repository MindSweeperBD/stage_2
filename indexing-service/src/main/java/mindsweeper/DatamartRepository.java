package mindsweeper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public final class DatamartRepository {
    private final Path root;
    private final Path pendingMarker;
    private final Path lockFile;
    private final MetadataDatabase metadataDatabase;
    private final InvertedIndexRepository invertedIndexRepository;

    public DatamartRepository(Path root) throws IOException, SQLException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.pendingMarker = this.root.resolve(".indexing-in-progress");
        this.lockFile = this.root.resolve(".index.lock");
        this.metadataDatabase = new MetadataDatabase(this.root.resolve("metadata.db"));
        this.invertedIndexRepository = new InvertedIndexRepository(
                this.root.resolve("inverted_index.json"));
        metadataDatabase.createDatabase();
    }

    public Path root() {
        return root;
    }

    public Path metadataPath() {
        return metadataDatabase.databasePath();
    }

    public Path invertedIndexPath() {
        return invertedIndexRepository.indexPath();
    }

    public boolean publicationPending() {
        return Files.exists(pendingMarker);
    }

    public AccessLock acquireWriteLock() throws IOException {
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            return new AccessLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public AccessLock acquireReadLock() throws IOException {
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock(0L, Long.MAX_VALUE, true);
            if (publicationPending()) {
                lock.release();
                channel.close();
                throw new IOException(
                        "An indexing publication is incomplete; wait for /index/rebuild before reading the datamart");
            }
            return new AccessLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            if (channel.isOpen()) {
                channel.close();
            }
            throw e;
        }
    }

    public Map<Integer, BookMetadata> loadMetadata() throws SQLException {
        return metadataDatabase.loadAll();
    }

    public Map<String, Set<Integer>> loadInvertedIndex() throws IOException {
        return invertedIndexRepository.load();
    }

    public void publish(Collection<BookMetadata> metadata,
                        Map<String, Set<Integer>> invertedIndex,
                        boolean replaceAll) throws IOException {
        Files.createDirectories(root);
        Path indexTemp = Files.createTempFile(root, "inverted-index-", ".tmp");
        Path backup = null;
        boolean oldIndexExists = Files.isRegularFile(invertedIndexPath());
        boolean wasAlreadyPending = publicationPending();
        boolean markerWritten = false;
        boolean indexReplaced = false;
        boolean committed = false;
        boolean rollbackSucceeded = true;
        boolean restored = true;

        try {
            Files.writeString(indexTemp, invertedIndexRepository.toJson(invertedIndex),
                    StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            if (oldIndexExists) {
                backup = Files.createTempFile(root, "inverted-index-backup-", ".json");
                Files.copy(invertedIndexPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.writeString(pendingMarker,
                    "publication in progress; readers must wait for a successful rebuild\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            markerWritten = true;

            try (Connection connection = metadataDatabase.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    metadataDatabase.apply(connection, metadata, replaceAll);
                    moveAtomically(indexTemp, invertedIndexPath());
                    indexReplaced = true;
                    connection.commit();
                    committed = true;
                } catch (IOException | SQLException | RuntimeException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        rollbackSucceeded = false;
                        failure.addSuppressed(rollbackError);
                    }
                    throw failure;
                }
            } catch (IOException | SQLException | RuntimeException failure) {
                if (!committed && indexReplaced) {
                    try {
                        if (oldIndexExists && backup != null && Files.exists(backup)) {
                            moveAtomically(backup, invertedIndexPath());
                            backup = null;
                        } else {
                            Files.deleteIfExists(invertedIndexPath());
                        }
                    } catch (IOException restoreError) {
                        restored = false;
                        failure.addSuppressed(restoreError);
                    }
                }

                if (markerWritten && rollbackSucceeded && restored && !wasAlreadyPending) {
                    try {
                        Files.deleteIfExists(pendingMarker);
                        markerWritten = false;
                    } catch (IOException markerError) {
                        failure.addSuppressed(markerError);
                    }
                }

                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IOException("Could not publish the datamart", failure);
            }

            Files.deleteIfExists(pendingMarker);
            markerWritten = false;
        } finally {
            Files.deleteIfExists(indexTemp);
            if (backup != null && !publicationPending()) {
                Files.deleteIfExists(backup);
            }
        }
    }

    public long totalSizeBytes() throws IOException {
        long total = 0L;
        if (Files.isRegularFile(metadataPath())) {
            total += Files.size(metadataPath());
        }
        if (Files.isRegularFile(invertedIndexPath())) {
            total += Files.size(invertedIndexPath());
        }
        return total;
    }

    public String lastUpdateFromFiles() throws IOException {
        if (!Files.isRegularFile(invertedIndexPath())
                && !Files.isRegularFile(metadataPath())) {
            return null;
        }
        long latest = 0L;
        if (Files.isRegularFile(metadataPath())) {
            latest = Math.max(latest, Files.getLastModifiedTime(metadataPath()).toMillis());
        }
        if (Files.isRegularFile(invertedIndexPath())) {
            latest = Math.max(latest, Files.getLastModifiedTime(invertedIndexPath()).toMillis());
        }
        return latest == 0L ? null : java.time.Instant.ofEpochMilli(latest).toString();
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Datamart filesystem does not support atomic index-file replacement", e);
        }
    }

    public static final class AccessLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private AccessLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                channel.close();
            }
        }
    }
}
