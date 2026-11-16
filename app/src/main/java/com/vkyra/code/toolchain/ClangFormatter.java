package com.vkyra.code.toolchain;

import com.vkyra.code.terminal.ManagedProcessRunner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Clang-Format 代码格式化工具
 */
public class ClangFormatter {
    
    private static final String CLANG_FORMAT_NAME = "clang-format";
    private static final String CLANG_FORMAT_CONFIG = ".clang-format";
    private static final String CLANG_FORMAT_CONFIG_ASSET = "clang-format-config";  // assets 中的配置文件名
    
    public interface FormatCallback {
        void onSuccess(String formattedCode);
        void onError(String errorMessage);
    }
    
    /**
     * 检查 clang-format 是否已安装（检查 clang 编译链中的 clang-format）
     */
    public static boolean isInstalled(Context context) {
        String clangDirName = ClangInstaller.getClangDirName(context);
        if (clangDirName == null) return false;
        File clangFormat = new File(context.getFilesDir(), clangDirName + "/bin/" + CLANG_FORMAT_NAME);
        return clangFormat.exists() && clangFormat.canExecute();
    }
    
    /**
     * 安装 clang-format（确保配置文件存在）
     * 注意：clang-format 二进制已包含在 clang 编译链中，无需单独复制
     */
    public static void install(Context context) throws IOException {
        String clangDirName = ClangInstaller.getClangDirName(context);
        if (clangDirName == null) return;
        File clangFormat = new File(context.getFilesDir(), clangDirName + "/bin/" + CLANG_FORMAT_NAME);
        File configFile = new File(context.getFilesDir(), CLANG_FORMAT_CONFIG);
        
        // clang-format 二进制已在 clang 编译链中，只需确保可执行
        if (clangFormat.exists()) {
            clangFormat.setExecutable(true, false);
        }
        
        // 创建默认配置文件
        if (!configFile.exists()) {
            createDefaultConfig(context, configFile);
        }
    }
    
    /**
     * 创建默认的 .clang-format 配置（从 assets 复制）
     */
    private static void createDefaultConfig(Context context, File configFile) throws IOException {
        copyAsset(context, CLANG_FORMAT_CONFIG_ASSET, configFile);
    }
    
    /**
     * 从 assets 复制文件
     */
    private static void copyAsset(Context context, String assetName, File destFile) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
    
    /**
     * 检查 .clang-format 配置文件是否有效（检查是否包含关键字）
     */
    private static boolean isValidClangFormatConfig(File configFile) {
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(configFile))) {
            String firstLine = reader.readLine();
            // 有效的配置文件应该以 # 或 BasedOnStyle 等开头
            return firstLine != null && (firstLine.startsWith("#") || firstLine.contains("BasedOnStyle") || firstLine.contains(":"));
        } catch (Exception e) {
            return false;
        }
    }
    
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ThreadPoolExecutor EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8),
            r -> new Thread(r, "Vkyra-formatter"));

    public static final class FormatTask implements AutoCloseable {
        private final ManagedProcessRunner runner = new ManagedProcessRunner(null);
        private final java.util.concurrent.atomic.AtomicReference<FormatCallback> callback;
        private final java.lang.ref.WeakReference<androidx.lifecycle.LifecycleOwner> owner;
        private final androidx.lifecycle.LifecycleEventObserver observer;
        private volatile java.util.concurrent.Future<?> future;
        private FormatTask(Context context, FormatCallback listener) {
            callback = new java.util.concurrent.atomic.AtomicReference<>(listener);
            owner = new java.lang.ref.WeakReference<>(context instanceof androidx.lifecycle.LifecycleOwner
                    ? (androidx.lifecycle.LifecycleOwner) context : null);
            observer = (source, event) -> { if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) cancel(); };
            MAIN.post(() -> {
                androidx.lifecycle.LifecycleOwner current = owner.get();
                if (current != null) {
                    if (current.getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.DESTROYED) cancel();
                    else current.getLifecycle().addObserver(observer);
                }
            });
        }
        public boolean isCancelled() { return runner.isCancelled(); }
        public void cancel() {
            callback.set(null); runner.cancel();
            java.util.concurrent.Future<?> submitted = future;
            if (submitted != null) submitted.cancel(true);
            EXECUTOR.purge(); detach();
        }
        @Override public void close() { cancel(); }
        private void detach() {
            MAIN.post(() -> {
                androidx.lifecycle.LifecycleOwner current = owner.get();
                if (current != null) current.getLifecycle().removeObserver(observer);
                owner.clear();
            });
        }
        private void deliver(FormatResult result) {
            MAIN.post(() -> {
                try {
                    androidx.lifecycle.LifecycleOwner current = owner.get();
                    FormatCallback listener = callback.getAndSet(null);
                    if (!isCancelled() && listener != null && (current == null || current.getLifecycle().getCurrentState()
                            != androidx.lifecycle.Lifecycle.State.DESTROYED)) {
                        if (result.success) listener.onSuccess(result.formattedCode); else listener.onError(result.errorMessage);
                    }
                } finally { detach(); }
            });
        }
    }

    public static FormatTask formatAsync(Context context, String code, FormatCallback callback) {
        FormatTask task = new FormatTask(context, callback);
        Context application = context.getApplicationContext();
        final Context formatContext = application == null ? context : application;
        try { task.future = EXECUTOR.submit(() -> task.deliver(format(formatContext, code, task.runner))); }
        catch (java.util.concurrent.RejectedExecutionException busy) { task.deliver(error("格式化任务过多，请稍后重试")); }
        return task;
    }

    /** Synchronous API for background callers. */
    public static FormatResult format(Context context, String code) {
        Context application = context.getApplicationContext();
        return format(application == null ? context : application, code, new ManagedProcessRunner(null));
    }

    private static FormatResult format(Context context, String code, ManagedProcessRunner runner) {
        try {
            if (code == null) return error("代码不能为空");
            String clangDirName = ClangInstaller.getClangDirName(context);
            if (clangDirName == null) return error("不支持的设备架构");
            File binary = new File(context.getFilesDir(), clangDirName + "/bin/" + CLANG_FORMAT_NAME);
            if (!binary.isFile()) return error("clang-format 未找到，请先安装 Clang 编译器");
            if (!binary.canExecute() && !binary.setExecutable(true, true)) return error("clang-format 不可执行");
            File config = new File(context.getFilesDir(), CLANG_FORMAT_CONFIG);
            synchronized (ClangFormatter.class) {
                if (!config.exists()) {
                    try { createDefaultConfig(context, config); }
                    catch (IOException ignored) { /* Google fallback remains available. */ }
                }
            }
            // Preserve user styles; clang-format itself validates YAML and returns diagnostics.
            ProcessBuilder command = new ProcessBuilder(binary.getPath(), config.isFile() ? "-style=file" : "-style=Google",
                    "-assume-filename=code.cpp").directory(context.getFilesDir());
            ManagedProcessRunner.Result execution = runner.run(command, 60_000, code, true);
            if (execution.cancelled) return error("格式化已取消或超时");
            if (execution.outputTruncated) return error("格式化输出超过 4M 字符限制，原代码已保留");
            if (execution.exitCode != 0) return error(execution.errorOutput.isEmpty()
                    ? "格式化失败，退出码: " + execution.exitCode : execution.errorOutput);
            FormatResult result = new FormatResult();
            result.success = true;
            result.formattedCode = execution.output;
            return result;
        } catch (Exception failure) { return error("格式化异常: " + failure.getMessage()); }
    }
    private static FormatResult error(String message) { FormatResult result = new FormatResult(); result.errorMessage = message; return result; }
    public static class FormatResult {
        public boolean success;
        public String formattedCode = "";
        public String errorMessage = "";
    }
}
