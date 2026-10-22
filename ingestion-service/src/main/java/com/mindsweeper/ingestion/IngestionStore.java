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
        Path dir = bookDir(id);

        try {
            return Files.isRegularFile(dir.resolve(".complete"))
                    && Files.isRegularFile(dir.resolve("header.txt"))
                    && Files.isRegularFile(dir.resolve("body.txt"))
                    && Files.size(dir.resolve("body.txt")) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized Path save(
            int id, BookDownloader.Book book) throws IOException {

        Path dir = bookDir(id);

        if (isComplete(id)) {
            return dir;
        }

        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve(".complete"));

        Path headerTmp = Files.createTempFile(
                dir, "header-", ".tmp");
        Path bodyTmp = Files.createTempFile(
                dir, "body-", ".tmp");

        try {
            Files.writeString(
                    headerTmp, book.header() + "\n",
                    StandardCharsets.UTF_8);

            Files.writeString(
                    bodyTmp, book.body() + "\n",
                    StandardCharsets.UTF_8);

            move(headerTmp, dir.resolve("header.txt"));
            move(bodyTmp, dir.resolve("body.txt"));

            Path marker = Files.createTempFile(
                    dir, "complete-", ".tmp");

            try {
                Files.writeString(
                        marker, "ok\n", StandardCharsets.UTF_8);
                move(marker, dir.resolve(".complete"));
            } finally {
                Files.deleteIfExists(marker);
            }

            return dir;

        } finally {
            Files.deleteIfExists(headerTmp);
            Files.deleteIfExists(bodyTmp);
        }
    }

    private static void move(Path source, Path destination)
            throws IOException {

        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<Integer> list() throws IOException {

        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(s -> s.matches("[1-9][0-9]*"))
                    .map(s -> {
                        try {
                            return Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    })
                    .filter(id -> id > 0)
                    .filter(this::isComplete)
                    .sorted()
                    .toList();
        }
    }
}
