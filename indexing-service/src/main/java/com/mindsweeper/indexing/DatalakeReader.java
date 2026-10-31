package com.mindsweeper.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DatalakeReader {
    private final Path root;

    public DatalakeReader(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public BookDocument readReadyBook(int id) throws IOException {
        if (id <= 0) {
            throw new IllegalArgumentException("book_id must be positive");
        }

        Path directory = root.resolve(Integer.toString(id)).normalize();
        if (!root.equals(directory.getParent())) {
            throw new IllegalArgumentException("Invalid book_id path");
        }

        Path marker = directory.resolve(".complete");
        Path headerFile = directory.resolve("header.txt");
        Path bodyFile = directory.resolve("body.txt");

        if (!Files.isRegularFile(marker)
                || !Files.isRegularFile(headerFile)
                || !Files.isRegularFile(bodyFile)) {
            throw new BookNotReadyException(
                    "Book " + id + " is not ready: .complete, header.txt and body.txt are required");
        }

        BasicFileAttributes markerBefore = Files.readAttributes(marker, BasicFileAttributes.class);
        BasicFileAttributes headerBefore = Files.readAttributes(headerFile, BasicFileAttributes.class);
        BasicFileAttributes bodyBefore = Files.readAttributes(bodyFile, BasicFileAttributes.class);

        String header = Files.readString(headerFile, StandardCharsets.UTF_8);
        String body = Files.readString(bodyFile, StandardCharsets.UTF_8);

        if (!Files.isRegularFile(marker)) {
            throw new BookNotReadyException("Book " + id + " changed while it was being read");
        }

        BasicFileAttributes markerAfter = Files.readAttributes(marker, BasicFileAttributes.class);
        BasicFileAttributes headerAfter = Files.readAttributes(headerFile, BasicFileAttributes.class);
        BasicFileAttributes bodyAfter = Files.readAttributes(bodyFile, BasicFileAttributes.class);

        if (!sameFileVersion(markerBefore, markerAfter)
                || !sameFileVersion(headerBefore, headerAfter)
                || !sameFileVersion(bodyBefore, bodyAfter)) {
            throw new BookNotReadyException("Book " + id + " changed while it was being read");
        }

        if (body.isBlank()) {
            throw new BookNotReadyException("Book " + id + " has an empty body");
        }

        return new BookDocument(
                id,
                header.strip(),
                body.strip(),
                bodyFile.toAbsolutePath().normalize().toString()
        );
    }

    public List<Integer> listCandidateBookIds() throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Datalake directory does not exist: " + root);
        }

        try (Stream<Path> paths = Files.list(root)) {
            return paths
                    .filter(Files::isDirectory)
                    .map(path -> parsePositiveId(path.getFileName().toString()))
                    .filter(id -> id > 0)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static int parsePositiveId(String value) {
        try {
            int id = Integer.parseInt(value);
            return id > 0 ? id : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean sameFileVersion(BasicFileAttributes before,
                                           BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && (before.fileKey() == null || after.fileKey() == null
                    || before.fileKey().equals(after.fileKey()));
    }

    public record BookDocument(int bookId, String header, String body, String bodyPath) {
    }
}
