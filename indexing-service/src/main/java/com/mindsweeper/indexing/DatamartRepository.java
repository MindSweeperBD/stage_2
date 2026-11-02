package com.mindsweeper.indexing;

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

        Path indexTemp = createTemporaryIndex(invertedIndex);
        Path backup = createBackupIfNeeded();
        PublicationState state = new PublicationState(
                backup != null,
                publicationPending()
        );

        try {
            writePendingMarker();
            executePublication(metadata, replaceAll, indexTemp, backup, state);
            removePendingMarker();
        } finally {
            cleanupTemporaryFiles(indexTemp, backup);
        }
    }

    private Path createTemporaryIndex(
            Map<String, Set<Integer>> invertedIndex) throws IOException {

        Path temp = Files.createTempFile(root, "inverted-index-", ".tmp");

        Files.writeString(
                temp,
                invertedIndexRepository.toJson(invertedIndex),
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        return temp;
    }

    private Path createBackupIfNeeded() throws IOException {

        if (!Files.isRegularFile(invertedIndexPath())) {
            return null;
        }

        Path backup = Files.createTempFile(
                root,
                "inverted-index-backup-",
                ".json"
        );
        Files.copy(
                invertedIndexPath(),
                backup,
                StandardCopyOption.REPLACE_EXISTING
        );
        return backup;
    }

    private void writePendingMarker() throws IOException {

        Files.writeString(
                pendingMarker,
                "publication in progress; readers must wait for a successful rebuild\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void executePublication(
            Collection<BookMetadata> metadata,
            boolean replaceAll,
            Path indexTemp,
            Path backup,
            PublicationState state) throws IOException {

        boolean indexReplaced = false;

        try (Connection connection = metadataDatabase.openConnection()) {

            connection.setAutoCommit(false);

            try {
                metadataDatabase.apply(connection, metadata, replaceAll);

                moveAtomically(indexTemp, invertedIndexPath());
                indexReplaced = true;

                connection.commit();

            } catch (IOException | SQLException | RuntimeException failure) {

                rollback(connection, failure);

                if (indexReplaced) {
                    restoreIndex(backup, failure);
                }
                cleanupPendingMarkerAfterFailure(state, failure);
                throwPublicationException(failure);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    private void restoreIndex(Path backup, Exception failure) {
        try {
            if (backup != null && Files.exists(backup)) {
                moveAtomically(backup, invertedIndexPath());
            } else {
                Files.deleteIfExists(invertedIndexPath());
            }
        } catch (IOException restoreError) {
            failure.addSuppressed(restoreError);
        }
    }

    private void cleanupPendingMarkerAfterFailure(PublicationState state, Exception failure) {
        if (state.wasAlreadyPending()) { return; }
        try {
            removePendingMarker();
        } catch (IOException markerError) {
            failure.addSuppressed(markerError);
        }
    }

    private void throwPublicationException(Exception failure) throws IOException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IOException(
                "Could not publish the datamart",
                failure
        );
    }

    private void removePendingMarker() throws IOException {
        Files.deleteIfExists(pendingMarker);
    }

    private void cleanupTemporaryFiles(Path indexTemp, Path backup) throws IOException {
        Files.deleteIfExists(indexTemp);

        if (backup != null && !publicationPending()) {
            Files.deleteIfExists(backup);
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

    private record PublicationState(
            boolean hadPreviousIndex,
            boolean wasAlreadyPending) {
    }
}
