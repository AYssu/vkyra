package com.vkyra.code.toolchain;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Project build metadata, independent of the editor page. Existing file format stays readable. */
public final class BuildConfiguration {
    public static final String CONFIG_FILE_NAME = ".compile_config";
    private static final long MAX_CONFIG_BYTES = 1024 * 1024;
    private BuildConfiguration() { }

    public static boolean isMultiFileMode(File directory) {
        return directory != null && new File(directory, CONFIG_FILE_NAME).isFile();
    }

    public static List<String> readMultiFileList(File directory) throws IOException {
        if (directory == null) return Collections.emptyList();
        File file = new File(directory, CONFIG_FILE_NAME);
        if (!file.exists()) return Collections.emptyList();
        if (file.length() > MAX_CONFIG_BYTES) throw new IOException("多文件编译配置超过 1 MiB 上限");
        List<String> files = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int characters = 0;
            while ((line = reader.readLine()) != null) {
                characters += line.length();
                if (characters > MAX_CONFIG_BYTES) throw new IOException("多文件编译配置超过上限");
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) files.add(line);
            }
        }
        return Collections.unmodifiableList(files);
    }

    /** Compatibility facade for display-only callers; build operations use the throwing method. */
    public static List<String> getMultiFileList(File directory) {
        try { return readMultiFileList(directory); }
        catch (IOException failure) { return Collections.emptyList(); }
    }
}
