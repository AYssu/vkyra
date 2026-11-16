package com.vkyra.code.signature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Existing exported files remain intact on copy/verification/cancellation failure. */
final class AtomicArtifactPublisher {
    interface Validator { void verify(File file) throws Exception; }
    static void publish(File source, File output, ExportCancellation cancellation, Validator validator) throws Exception {
        try (InputStream input = Files.newInputStream(source.toPath())) { publish(input, output, cancellation, validator); }
    }
    static void publish(InputStream input, File output, ExportCancellation cancellation, Validator validator) throws Exception {
        File destination = output.getAbsoluteFile();
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Export destination directory does not exist");
        File stage = File.createTempFile(".vkyra-export-", ".apk", parent);
        try {
            try (FileOutputStream target = new FileOutputStream(stage)) {
                copy(input, target, cancellation);
                target.flush(); target.getFD().sync();
            }
            cancellation.check();
            validator.verify(stage);
            cancellation.commit(() -> Files.move(stage.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
        } finally { Files.deleteIfExists(stage.toPath()); }
    }
    static void copy(InputStream input, FileOutputStream output, ExportCancellation cancellation) throws IOException {
        byte[] buffer = new byte[65536];
        for (int read; (read = input.read(buffer)) != -1;) { cancellation.check(); output.write(buffer, 0, read); }
        cancellation.check();
    }
}
