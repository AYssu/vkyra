package com.vkyra.code.signature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Cancellable repackaging with immutable metadata, per-job staging and atomic publication. */
public class ApkRepackager implements AutoCloseable {
    private final Context application;
    private volatile OnProgressListener progressListener;
    private ApkExportTask active;
    private boolean closed;
    public interface OnProgressListener {
        void onProgress(String message, int progress);
        void onSuccess(File signedApk);
        void onError(String error);
    }
    public ApkRepackager(Context context) { application = context.getApplicationContext(); }
    public synchronized ApkExportTask repackage(File sourceApk, AppInfoDialog.AppInfo appInfo) {
        File outputDirectory = new File(application.getCacheDir(), "export-results");
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) throw new IllegalStateException("Cannot create export directory");
        return repackage(sourceApk, appInfo, new File(outputDirectory, "vkyra-" + UUID.randomUUID() + ".apk"));
    }
    public synchronized ApkExportTask repackage(File sourceApk, AppInfoDialog.AppInfo appInfo, File output) {
        if (closed) throw new IllegalStateException("Repackager is closed");
        if (active != null && !active.isDone()) active.cancel();
        File source = sourceApk.getAbsoluteFile(), destination = output.getAbsoluteFile();
        AppInfoDialog.AppInfo snapshot = ApkExportManager.copyInfo(appInfo);
        Context context = application;
        active = ApkExportTask.submit(new File(context.getCacheDir(), "apk-jobs"), destination,
                new ApkExportTask.Listener() {
                    public void onProgress(String message, int progress) { OnProgressListener target = progressListener; if (target != null) target.onProgress(message, progress); }
                    public void onSuccess(File file) { OnProgressListener target = progressListener; progressListener = null; if (target != null) target.onSuccess(file); }
                    public void onError(String error) { OnProgressListener target = progressListener; progressListener = null; if (target != null) target.onError(error); }
                }, runnable -> new Handler(Looper.getMainLooper()).post(runnable),
                (workspace, cancellation, progress) -> repackageInWorkspace(context, source, snapshot, workspace, cancellation, progress));
        return active;
    }
    static File repackageInWorkspace(Context context, File source, AppInfoDialog.AppInfo appInfo,
            ExportWorkspace workspace, ExportCancellation cancellation, ApkExportTask.Progress progress) throws Exception {
        cancellation.check(); progress.update("正在修改 APK 信息", 70);
        File modified = new SimpleApkModifier(workspace.directory()).modifyApk(source, appInfo, cancellation);
        cancellation.check(); progress.update("正在签名并验证 APK", 85);
        return new CustomApkSigner(context).signApk(modified, cancellation);
    }
    public synchronized void setProgressListener(OnProgressListener listener) { if (!closed) progressListener = listener; }
    public synchronized void cancel() { progressListener = null; if (active != null) active.cancel(); }
    public void cleanup() { cancel(); }
    @Override public synchronized void close() { closed = true; cancel(); }
}
