package com.vkyra.code.signature;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;

/** Modifies only a job-local copy. Caller owns the workspace and cancellation token. */
public class SimpleApkModifier {
    private final File workDir;
    public SimpleApkModifier(Context context) {
        this(new File(context.getApplicationContext().getCacheDir(), "apk-modification"));
    }
    SimpleApkModifier(File workDir) { this.workDir = workDir; }
    public File modifyApk(File sourceApk, AppInfoDialog.AppInfo appInfo) throws Exception {
        return modifyApk(sourceApk, appInfo, new ExportCancellation());
    }
    File modifyApk(File sourceApk, AppInfoDialog.AppInfo appInfo, ExportCancellation cancellation) throws Exception {
        cancellation.check();
        Files.createDirectories(workDir.toPath());
        File modified = File.createTempFile("modified-", ".apk", workDir);
        boolean success = false;
        try {
            try (java.io.InputStream input = Files.newInputStream(sourceApk.toPath());
                 java.io.FileOutputStream output = new java.io.FileOutputStream(modified)) {
                AtomicArtifactPublisher.copy(input, output, cancellation);
            }
            cancellation.check();
            if (!new BinaryXmlEditor().modifyApkWithApkModule(modified, appInfo))
                throw new java.io.IOException("APK metadata modification failed");
            cancellation.check();
            success = true;
            return modified;
        } finally { if (!success) Files.deleteIfExists(modified.toPath()); }
    }
}
