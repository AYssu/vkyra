package com.vkyra.code.toolchain;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded private compiler outputs. In-flight artifacts are never pruned. */
final class CompilerArtifacts {
    static final int RETAINED_BUILDS = 8;
    private static final String MARKER = ".vkyra-compiler-output";
    private static final Set<Path> ACTIVE = new HashSet<>();
    private CompilerArtifacts() { }

    // Planning is pure: callers can expose this exact path to compile_before on the UI thread.
    static String plan(File files) {
        return new File(new File(files, "tmp/build-vkyra-" + UUID.randomUUID()), "a.out").getAbsolutePath();
    }

    static synchronized File begin(File files, String output) throws IOException {
        Path target = new File(output).toPath().toAbsolutePath().normalize();
        Path directory = target.getParent();
        Path tmp = new File(files, "tmp").toPath().toAbsolutePath().normalize();
        if (!"a.out".equals(target.getFileName().toString()) || directory == null
                || !tmp.equals(directory.getParent()) || !isNewName(directory.getFileName().toString())) return null;
        if (!ACTIVE.add(directory)) throw new IOException("编译输出已由另一个任务占用");
        boolean created = false;
        try {
            Files.createDirectories(tmp);
            // A planned output is single-use; refusing an existing path protects previous outputs.
            Files.createDirectory(directory);
            created = true;
            Files.createFile(directory.resolve(MARKER));
            return directory.toFile();
        } catch (IOException failure) {
            ACTIVE.remove(directory);
            if (created) try { deleteTree(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static synchronized void finish(File directory, boolean success) throws IOException {
        if (directory == null) return;
        Path owned = directory.toPath().toAbsolutePath().normalize();
        try {
            if (!success) { deleteTree(owned); return; }
            Files.setLastModifiedTime(owned.resolve(MARKER), FileTime.fromMillis(System.currentTimeMillis()));
            List<Path> candidates = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(owned.getParent())) {
                for (Path entry : entries) if (managedDirectory(entry)) candidates.add(entry);
            }
            candidates.sort(Comparator.comparingLong(CompilerArtifacts::modified).reversed());
            // Keep this publication even if another artifact has an identical timestamp.
            int retained = 1;
            for (Path candidate : candidates) {
                if (candidate.equals(owned) || ACTIVE.contains(candidate)) continue;
                if (retained++ >= RETAINED_BUILDS) deleteTree(candidate);
            }
        } finally { ACTIVE.remove(owned); }
    }

    private static boolean isNewName(String name) {
        return name.matches("build-vkyra-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static boolean managedDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) return false;
        String name = directory.getFileName().toString();
        if (isNewName(name)) return Files.isRegularFile(directory.resolve(MARKER))
                && !Files.isSymbolicLink(directory.resolve(MARKER));
        // Compatibility cleanup for the previous Files.createTempDirectory("build-") outputs.
        if (!name.matches("build-[0-9]{6,}")) return false;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String child = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry)
                        || !(child.equals("a.out") || child.matches("\\.vkyra-build-.*\\.out"))) return false;
            }
        }
        return true;
    }
    private static long modified(Path path) {
        File marker = path.resolve(MARKER).toFile();
        return marker.isFile() ? marker.lastModified() : path.toFile().lastModified();
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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
