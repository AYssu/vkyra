package com.vkyra.code.toolchain;

import com.vkyra.code.plugin.PluginManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.terminal.ManagedProcessRunner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Application-owned compiler jobs. Pages observe results and may cancel their own task. */
public final class CppCompiler {
    private static final String[] CPP_ONLY_ARGS = {"-std=c++", "-stdlib=", "-fcoroutines", "-fmodules"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "Vkyra-compiler"));
    private CppCompiler() { }

    public interface CompileCallback {
        void onSuccess(String outputPath);
        void onError(String errorMessage);
    }

    public static final class CompileTask implements AutoCloseable {
        private final ManagedProcessRunner runner = new ManagedProcessRunner(pid -> {
            try { Os.kill(pid, OsConstants.SIGKILL); } catch (android.system.ErrnoException gone) { }
        });
        private final AtomicReference<CompileCallback> callback;
        private final WeakReference<LifecycleOwner> owner;
        private final LifecycleEventObserver observer;
        private volatile Future<?> future;
        private CompileTask(Context context, CompileCallback callback) {
            this.callback = new AtomicReference<>(callback);
            LifecycleOwner lifecycleOwner = context instanceof LifecycleOwner ? (LifecycleOwner) context : null;
            owner = new WeakReference<>(lifecycleOwner);
            observer = (source, event) -> { if (event == Lifecycle.Event.ON_DESTROY) cancel(); };
            if (lifecycleOwner != null) MAIN.post(() -> {
                LifecycleOwner current = owner.get();
                if (current != null) {
                    if (current.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) cancel();
                    else current.getLifecycle().addObserver(observer);
                }
            });
        }
        public boolean isCancelled() { return runner.isCancelled(); }
        public void cancel() {
            callback.set(null);
            runner.cancel();
            Future<?> submitted = future;
            if (submitted != null) submitted.cancel(true);
            EXECUTOR.purge();
            detach();
        }
        @Override public void close() { cancel(); }
        private void detach() {
            MAIN.post(() -> {
                LifecycleOwner current = owner.get();
                if (current != null) current.getLifecycle().removeObserver(observer);
                owner.clear();
            });
        }
        private void deliver(CompileResult result) {
            MAIN.post(() -> {
                LifecycleOwner current = owner.get();
                CompileCallback listener = callback.getAndSet(null);
                if (!isCancelled() && listener != null
                        && (current == null || current.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED)) {
                    if (result.success) listener.onSuccess(result.outputPath);
                    else listener.onError(result.errorMessage);
                }
                detach();
            });
        }
    }

    public static CompileTask compileAsync(Context context, String sourceFilePath, CompileCallback callback) {
        return submit(context, sourceFilePath, null, false, true, callback);
    }
    /** The hook, compiler, success callback and terminal all use this planned output. */
    public static String newOutputPath(Context context) {
        return CompilerArtifacts.plan(application(context).getFilesDir());
    }
    public static CompileTask compileAsync(Context context, String sourceFilePath, String outputPath,
            CompileCallback callback) {
        return submit(context, sourceFilePath, outputPath, false, true, callback);
    }
    public static CompileTask compileToPathAsync(Context context, String sourceFilePath, String outputPath, CompileCallback callback) {
        return submit(context, sourceFilePath, outputPath, false, false, callback);
    }
    public static CompileTask compileToPathWithOllvmAsync(Context context, String sourceFilePath, String outputPath, CompileCallback callback) {
        return submit(context, sourceFilePath, outputPath, true, false, callback);
    }
    private static CompileTask submit(Context context, String source, String output, boolean ollvm,
            boolean projectMode, CompileCallback callback) {
        CompileTask task = new CompileTask(context, callback);
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        final Context buildContext = application;
        try {
            task.future = EXECUTOR.submit(() -> task.deliver(compileInternal(buildContext, source, output, ollvm, projectMode, task.runner)));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            task.deliver(failure("编译任务过多，请等待当前任务完成"));
        }
        return task;
    }

    public static CompileResult compile(Context context, String sourceFilePath) {
        return compileInternal(application(context), sourceFilePath, null, false, true, new ManagedProcessRunner(null));
    }
    public static CompileResult compileToPath(Context context, String sourceFilePath, String outputPath, boolean useOllvm) {
        return compileInternal(application(context), sourceFilePath, outputPath, useOllvm, false, new ManagedProcessRunner(null));
    }
    /** Background callers can share cancellation ownership with export/package jobs. */
    public static CompileResult compileToPath(Context context, String source, String output, boolean ollvm,
            ManagedProcessRunner runner) {
        if (runner == null) throw new IllegalArgumentException("runner is required");
        return compileInternal(application(context), source, output, ollvm, false, runner);
    }

    private static CompileResult compileInternal(Context context, String source, String output, boolean ollvm,
            boolean projectMode, ManagedProcessRunner runner) {
        File ownedOutput = null;
        boolean success = false;
        try {
            if (runner.isCancelled()) return failure("编译已取消");
            if (output == null) output = newOutputPath(context);
            ownedOutput = CompilerArtifacts.begin(context.getFilesDir(), output);
            List<String> sources = new ArrayList<>();
            File file = new File(source);
            if (projectMode && BuildConfiguration.isMultiFileMode(file.getParentFile())) {
                for (String path : BuildConfiguration.readMultiFileList(file.getParentFile())) {
                    File candidate = new File(path);
                    sources.add(candidate.isAbsolute() ? candidate.getPath() : new File(file.getParentFile(), path).getPath());
                }
            }
            if (sources.isEmpty()) sources.add(source);
            CompileResult result = compileSources(context, sources, output, ollvm, runner);
            success = result.success;
            return result;
        } catch (Exception failure) { return failure("编译配置失败: " + failure.getMessage()); }
        finally {
            try { CompilerArtifacts.finish(ownedOutput, success); }
            catch (java.io.IOException cleanup) { android.util.Log.w("CppCompiler", "Cannot clean private build outputs", cleanup); }
        }
    }

    public static CompileResult compileMultiFiles(Context context, List<String> sourceFiles, String outputPath) {
        return compileSources(application(context), sourceFiles, outputPath, false, new ManagedProcessRunner(null));
    }

    private static CompileResult compileSources(Context context, List<String> sources, String outputPath,
            boolean ollvm, ManagedProcessRunner runner) {
        if (sources == null || sources.isEmpty()) return failure("没有源文件");
        if (runner.isCancelled()) return failure("编译已取消");
        File staged = null;
        try {
            String clangRoot = ClangInstaller.getClangDir(context);
            if (clangRoot == null) return failure("不支持的设备架构");
            File files = context.getFilesDir();
            File tmp = new File(files, "tmp");
            Files.createDirectories(tmp.toPath());
            File output = new File(outputPath).getAbsoluteFile();
            Files.createDirectories(output.getParentFile().toPath());
            staged = File.createTempFile(".vkyra-build-", ".out", output.getParentFile());
            Files.delete(staged.toPath());
            List<String> command = createCommand(context, clangRoot, sources, staged.getPath(), ollvm);
            ProcessBuilder process = new ProcessBuilder(command);
            String pluginPaths = PluginManager.getEnabledPluginPaths(context);
            String tools = BuildToolsInstaller.isInstalled(context) ? BuildToolsInstaller.getBuildToolsDir(context) + "/bin:" : "";
            String path = files + ":" + clangRoot + "/bin:" + tools + "/system/bin";
            process.environment().put("PATH", pluginPaths.isEmpty() ? path : pluginPaths + ":" + path);
            process.environment().put("HOME", files.getPath());
            process.environment().put("TMPDIR", tmp.getPath());
            process.environment().put("TEMP", tmp.getPath());
            process.environment().put("LD_LIBRARY_PATH", clangRoot + "/lib");
            process.directory(tmp);
            ManagedProcessRunner.Result result = runner.run(process, 30 * 60 * 1000L);
            if (result.cancelled) return failure("编译已取消");
            if (result.exitCode != 0) return failure(result.output.isEmpty() ? "编译失败，退出码: " + result.exitCode : result.output);
            if (!staged.isFile() || staged.length() == 0) return failure("编译命令未生成可执行产物");
            if (!staged.setExecutable(true, true)) return failure("无法设置编译产物执行权限");
            try {
                Files.move(staged.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                Files.move(staged.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            CompileResult success = new CompileResult(); success.success = true; success.outputPath = output.getPath();
            return success;
        } catch (Exception error) { return failure(runner.isCancelled() ? "编译已取消" : "编译异常: " + error.getMessage()); }
        finally { if (staged != null) staged.delete(); }
    }

    static List<String> compilerFlags(String arguments, boolean cFile) {
        List<String> result = new ArrayList<>();
        for (String argument : CompilerArguments.parse(arguments)) {
            boolean cppOnly = false;
            if (cFile) for (String prefix : CPP_ONLY_ARGS) if (argument.startsWith(prefix)) cppOnly = true;
            if (!cppOnly) result.add(argument);
        }
        return result;
    }

    public static List<String> createCommand(Context context, String clangRoot, List<String> sources, String output, boolean ollvm) {
        boolean cFile = sources.get(0).endsWith(".c");
        List<String> command = new ArrayList<>();
        command.add(clangRoot + "/bin/" + (cFile ? "clang" : "clang++"));
        command.addAll(sources);
        command.add("-I" + new File(sources.get(0)).getParent());
        PluginManager.PluginLibInfo plugins = PluginManager.getEnabledPluginLibs(context);
        for (String include : plugins.includeDirs) command.add("-I" + include);
        for (String lib : plugins.libDirs) command.add("-L" + lib);
        command.addAll(compilerFlags(AppPreferences.getClangArgs(context), cFile));
        if (ollvm) command.addAll(CompilerArguments.parse(AppPreferences.getOllvmArgs(context)));
        command.add("-o"); command.add(output);
        for (String name : plugins.libNames) command.add("-l" + name);
        return command;
    }
    private static Context application(Context context) {
        Context app = context.getApplicationContext(); return app == null ? context : app;
    }
    private static CompileResult failure(String message) { CompileResult result = new CompileResult(); result.errorMessage = message; return result; }
    public static class CompileResult {
        public boolean success;
        public String outputPath = "";
        public String errorMessage = "";
    }
}
