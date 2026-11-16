package com.vkyra.code.lsp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Per-process writable PCH storage; Android has no desktop /tmp directory. */
final class ClangdProcessEnvironment {
    private ClangdProcessEnvironment() { }

    static File prepare(ProcessBuilder process, File appCache) throws IOException {
        // Reuse one app cache directory rather than accumulating one directory per restart.
        // clangd owns the temporary PCH files within it and removes them on normal shutdown.
        File temporary = new File(appCache, "clangd-tmp");
        Files.createDirectories(temporary.toPath());
        if (!temporary.isDirectory() || !temporary.canWrite()) {
            throw new IOException("Clangd temporary directory is unavailable");
        }
        String path = temporary.getAbsolutePath();
        process.environment().put("TMPDIR", path);
        process.environment().put("TEMP", path);
        process.environment().put("TMP", path);
        return temporary;
    }
}
