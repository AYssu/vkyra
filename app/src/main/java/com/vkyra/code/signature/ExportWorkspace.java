package com.vkyra.code.signature;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** A job may clean only its own randomly allocated tree; symbolic links are never followed. */
public final class ExportWorkspace implements AutoCloseable {
    private final Path directory;
    public ExportWorkspace(File parent) throws IOException {
        Files.createDirectories(parent.toPath());
        directory = Files.createTempDirectory(parent.toPath(), "export-");
    }
    public File directory() { return directory.toFile(); }
    public File file(String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Workspace name must be one path component");
        return directory.resolve(name).toFile();
    }
    @Override public void close() throws IOException {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
