package com.vkyra.code.lsp;

import com.vkyra.code.App;

import android.content.Context;
import com.google.gson.Gson;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CompilerArguments;
import com.vkyra.code.toolchain.HeaderDirManager;
import com.vkyra.code.plugin.PluginManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App-owned fallback database. Never writes a project's .clangd or compilation database. */
public final class ClangdCompilationDatabase {
    private ClangdCompilationDatabase() {}

    /** Run off-main before launching clangd. Existing project configuration always wins. */
    public static File prepare(Context context, File workspace) throws Exception {
        if (hasProjectConfiguration(workspace)) return null;
        String clang = ClangInstaller.getClangDir(context);
        if (clang == null) return null;
        File cache = new File(context.getCacheDir(), "clangd-databases/" + hash(workspace.getCanonicalPath()));
        Files.createDirectories(cache.toPath());
        List<String> arguments = new ArrayList<>();
        arguments.add(new File(clang, "bin/clang++").getAbsolutePath());
        arguments.add("-std=c++17");
        arguments.add(AppPreferences.getTargetTriple(context));
        arguments.add("--sysroot=" + clang + "/sysroot");
        arguments.add("-resource-dir=" + clang + "/lib/clang/" + ClangInstaller.getClangResourceMajor(context));
        arguments.add("-isystem"); arguments.add(clang + "/include/c++/v1");
        arguments.add("-isystem"); arguments.add(clang + "/sysroot/usr/include");
        for (String include : HeaderDirManager.getHeaderDirs(context)) arguments.add("-I" + include);
        for (String include : PluginManager.getEnabledPluginLibs(context).includeDirs) arguments.add("-I" + include);
        arguments.add("-I" + workspace.getAbsolutePath());
        // clangd interpolates the nearest command for newly opened files, including subdirectories.
        File source = new File(workspace, "vkyra-fallback.cpp");
        arguments.add("-c"); arguments.add(source.getAbsolutePath());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("directory", workspace.getAbsolutePath());
        entry.put("file", source.getAbsolutePath());
        entry.put("arguments", arguments);
        File temporary = File.createTempFile("database-", ".tmp", cache);
        try {
            Files.write(temporary.toPath(), new Gson().toJson(Collections.singletonList(entry)).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), new File(cache, "compile_commands.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return cache;
    }

    public static boolean hasProjectConfiguration(File workspace) {
        return new File(workspace, ".clangd").exists()
                || new File(workspace, "compile_commands.json").exists()
                || new File(workspace, "compile_flags.txt").exists()
                || new File(workspace, "build/compile_commands.json").exists()
                || new File(workspace, ".akode/compile_commands.json").exists();
    }

    private static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
