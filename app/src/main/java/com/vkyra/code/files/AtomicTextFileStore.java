package com.vkyra.code.files;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Same-directory, fsynced, atomic replacement. A failed write never truncates the old file. */
public final class AtomicTextFileStore {
    private AtomicTextFileStore() {}

    public static TextFileCodec.Document read(File file) throws IOException {
        return TextFileCodec.decode(Files.readAllBytes(file.toPath()));
    }

    public static void write(File file, String text) throws IOException {
        File target = file.getCanonicalFile(); // Follow an editor-opened symlink; preserve the link itself.
        byte[] bytes = target.exists() ? read(target).encode(text) : TextFileCodec.encodeNew(text);
        writeBytes(target.toPath(), bytes);
    }

    static void writeBytes(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("目标目录不存在");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target)) {
            throw new IOException("目标不是普通文件");
        }
        Path staging = Files.createTempFile(parent, ".vkyra-save-", ".tmp");
        try {
            if (Files.exists(target)) copyPermissions(target, staging);
            try (FileOutputStream stream = new FileOutputStream(staging.toFile())) {
                stream.write(bytes);
                stream.flush();
                stream.getFD().sync();
            }
            // No copy/truncate fallback: if atomic commit is unsupported, keep the previous file.
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void copyPermissions(Path from, Path to) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(from);
            Files.setPosixFilePermissions(to, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Emulated/external filesystems may not expose POSIX attributes.
        }
    }
}
