package com.vkyra.code.signature;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.vkyra.code.toolchain.CppCompiler;
import com.vkyra.code.terminal.ManagedProcessRunner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;

/** Complete application-owned export pipeline. UI passes a request snapshot and owns its task. */
public final class ApkExportManager {
    private final Context application;
    public ApkExportManager(Context context) { application = context.getApplicationContext(); }
    public static final class Request {
        public final File sourceFile;
        public final File customExecutable;
        public final File outputFile;
        private final AppInfoDialog.AppInfo appInfo;
        public Request(File sourceFile, File customExecutable, File outputFile, AppInfoDialog.AppInfo appInfo) {
            if (sourceFile == null && customExecutable == null) throw new IllegalArgumentException("Missing source or executable");
            if (outputFile == null) throw new IllegalArgumentException("Missing export destination");
            this.sourceFile = sourceFile == null ? null : sourceFile.getAbsoluteFile();
            this.customExecutable = customExecutable == null ? null : customExecutable.getAbsoluteFile();
            this.outputFile = outputFile.getAbsoluteFile();
            this.appInfo = copyInfo(appInfo);
        }
    }
    public ApkExportTask export(Request request, ApkExportTask.Listener listener) {
        Context context = application;
        return ApkExportTask.submit(new File(context.getCacheDir(), "apk-jobs"), request.outputFile, listener,
                runnable -> new Handler(Looper.getMainLooper()).post(runnable),
                (workspace, cancellation, progress) -> {
                    ManagedProcessRunner compiler = new ManagedProcessRunner(null);
                    cancellation.setCancellationHook(compiler::cancel);
                    try {
                        File executable = workspace.file("executable");
                        if (request.customExecutable != null) {
                            progress.update("正在读取可执行文件", 10);
                            copyFile(request.customExecutable, executable, cancellation);
                        } else {
                            progress.update("正在编译源代码", 10);
                            cancellation.check();
                            CppCompiler.CompileResult result = CppCompiler.compileToPath(context,
                                    request.sourceFile.getPath(), executable.getPath(), true, compiler);
                            if (!result.success) throw new java.io.IOException("Compilation failed: " + result.errorMessage);
                        }
                        cancellation.check();
                        if (!executable.isFile() || executable.length() == 0) throw new java.io.IOException("Empty executable");
                        progress.update("正在准备 APK 模板", 40);
                        File template = workspace.file("template.apk");
                        try (InputStream input = context.getAssets().open("demo.apk"); FileOutputStream output = new FileOutputStream(template)) {
                            AtomicArtifactPublisher.copy(input, output, cancellation);
                        }
                        cancellation.check();
                        progress.update("正在加入可执行文件", 55);
                        // Direct entry replacement avoids a second archive extraction parser and shared directories.
                        try (ZipFile archive = new ZipFile(template)) {
                            ZipParameters parameters = new ZipParameters();
                            parameters.setFileNameInZip("assets/bin/executable"); parameters.setOverrideExistingFilesInZip(true);
                            archive.addFile(executable, parameters);
                        }
                        cancellation.check();
                        return ApkRepackager.repackageInWorkspace(context, template, request.appInfo, workspace, cancellation, progress);
                    } finally {
                        compiler.cancel();
                        compiler.awaitStopped();
                        cancellation.setCancellationHook(null);
                    }
                });
    }
    private static void copyFile(File source, File output, ExportCancellation cancellation) throws Exception {
        if (!source.isFile() || source.length() == 0) throw new java.io.IOException("Executable is missing or empty");
        try (InputStream input = Files.newInputStream(source.toPath()); FileOutputStream target = new FileOutputStream(output)) {
            AtomicArtifactPublisher.copy(input, target, cancellation);
        }
    }
    static AppInfoDialog.AppInfo copyInfo(AppInfoDialog.AppInfo source) {
        if (source == null) throw new IllegalArgumentException("Missing APK metadata");
        Bitmap icon = source.icon;
        if (icon != null) {
            if (icon.isRecycled()) throw new IllegalArgumentException("Icon bitmap has been recycled");
            icon = icon.copy(Bitmap.Config.ARGB_8888, false);
            if (icon == null) throw new IllegalArgumentException("Cannot snapshot APK icon");
        }
        return new AppInfoDialog.AppInfo(icon, source.appName, source.packageName, source.versionCode, source.versionInfo);
    }
}
