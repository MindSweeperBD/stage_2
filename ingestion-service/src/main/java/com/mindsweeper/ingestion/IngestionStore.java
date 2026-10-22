package com.mindsweeper.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class IngestionStore {

    private final Path root;

    public IngestionStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path bookDir(int id) {
        return root.resolve(Integer.toString(id));
    }

    public boolean isComplete(int id) {

        Path directory = bookDir(id);

        try {

            return Files.isRegularFile(
                    directory.resolve(".complete")
            )
            && Files.isRegularFile(
                    directory.resolve("header.txt")
            )
            && Files.isRegularFile(
                    directory.resolve("body.txt")
            )
            && Files.size(
                    directory.resolve("body.txt")
            ) > 0;

        } catch (IOException e) {
            return false;
        }
    }

    public synchronized Path save(
            int id,
            BookDownloader.Book book
    ) throws IOException {

        if (isComplete(id)) {
            return bookDir(id);
        }

        if (book == null
                || book.header() == null
                || book.body() == null
                || book.body().isBlank()) {

            throw new IOException("Invalid book content");
        }

        Path directory = bookDir(id);

        Files.createDirectories(directory);

        Files.deleteIfExists(
                directory.resolve(".complete")
        );

        Path headerTemp = Files.createTempFile(
                directory, "header-", ".tmp"
        );

        Path bodyTemp = Files.createTempFile(
                directory, "body-", ".tmp"
        );

        try {

            Files.writeString(
                    headerTemp,
                    book.header(),
                    StandardCharsets.UTF_8
            );

            Files.writeString(
                    bodyTemp,
                    book.body(),
                    StandardCharsets.UTF_8
            );

            move(
                    headerTemp,
                    directory.resolve("header.txt")
            );

            move(
                    bodyTemp,
                    directory.resolve("body.txt")
            );

            Path markerTemp = Files.createTempFile(
                    directory, "complete-", ".tmp"
            );

            try {

                Files.writeString(
                        markerTemp,
                        "complete\n",
                        StandardCharsets.UTF_8
                );

                move(
                        markerTemp,
                        directory.resolve(".complete")
                );

            } finally {
                Files.deleteIfExists(markerTemp);
            }

            return directory;

        } finally {

            Files.deleteIfExists(headerTemp);
            Files.deleteIfExists(bodyTemp);
        }
    }

    private static void move(
            Path source,
            Path destination
    ) throws IOException {

        Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    public List<Integer> list() throws IOException {

        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(root)) {

            return stream
                    .filter(Files::isDirectory)
                    .map(path ->
                            path.getFileName().toString()
                    )
                    .map(name -> {
                        try {
                            return Integer.parseInt(name);
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    })
                    .filter(id ->
                            id > 0 && isComplete(id)
                    )
                    .sorted()
                    .toList();
        }
    }

    public record StorageStats(
            long books,
            long files,
            long directories,
            long bytes
    ) {}

    public StorageStats stats() throws IOException {

        if (!Files.exists(root)) {
            return new StorageStats(0, 0, 0, 0);
        }

        long files = 0;
        long directories = 0;
        long bytes = 0;

        try (Stream<Path> stream = Files.walk(root)) {

            for (Path path :
                    (Iterable<Path>) stream::iterator) {

                if (Files.isDirectory(path)) {

                    directories++;

                } else if (Files.isRegularFile(path)) {

                    files++;
                    bytes += Files.size(path);
                }
            }
        }

        return new StorageStats(
                list().size(),
                files,
                directories,
                bytes
        );
    }
}
