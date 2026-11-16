package com.vkyra.code.signature;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.junit.Test;

public class ApkExportInstrumentedTest {
    @Test public void customExecutableExportsVerifiedMetadataAndPreservesPriorOutputOnFailure() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "export-regression-").toFile();
        File executable = new File(directory, "program"), output = new File(directory, "output.apk");
        // Packaging fixture only: this does not establish that a generated native program runs.
        Files.write(executable.toPath(), new byte[]{0x7f,'E','L','F',2,1,1,0});
        AppInfoDialog.AppInfo info = new AppInfoDialog.AppInfo(null, "Export regression", "com.vkyra.export.regression", 7, "7.0.0");
        CountDownLatch complete = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        ApkExportTask task = new ApkExportManager(context).export(new ApkExportManager.Request(null, executable, output, info),
                new ApkExportTask.Listener() {
                    public void onProgress(String message,int progress) { assertSame(Looper.getMainLooper(),Looper.myLooper()); }
                    public void onSuccess(File file) { assertSame(Looper.getMainLooper(),Looper.myLooper()); complete.countDown(); }
                    public void onError(String error) { failure.set(error); complete.countDown(); }
                });
        try {
            assertTrue(complete.await(60,TimeUnit.SECONDS)); assertNull(failure.get());
            assertTrue(task.awaitFinished(5,TimeUnit.SECONDS)); SignedApkVerifier.verify(output,null);
            PackageInfo exported = context.getPackageManager().getPackageArchiveInfo(output.getPath(),0);
            assertNotNull(exported); assertEquals("com.vkyra.export.regression",exported.packageName);
            assertEquals(7,exported.versionCode); assertEquals("7.0.0",exported.versionName);
            try (ZipFile archive = new ZipFile(output);
                 java.io.InputStream input = archive.getInputStream(archive.getEntry("assets/bin/executable"));
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; for(int read; (read=input.read(buffer))!=-1;) bytes.write(buffer,0,read);
                assertArrayEquals(Files.readAllBytes(executable.toPath()),bytes.toByteArray());
            }
            byte[] previous = Files.readAllBytes(output.toPath());
            CountDownLatch rejected = new CountDownLatch(1);
            ApkExportTask broken = new ApkExportManager(context).export(
                    new ApkExportManager.Request(null,new File(directory,"missing"),output,info),new ApkExportTask.Listener() {
                        public void onProgress(String message,int progress) { }
                        public void onSuccess(File file) { failure.set("Missing executable unexpectedly succeeded"); rejected.countDown(); }
                        public void onError(String error) { rejected.countDown(); }
                    });
            try { assertTrue(rejected.await(10,TimeUnit.SECONDS)); assertNull(failure.get()); assertTrue(broken.awaitFinished(5,TimeUnit.SECONDS)); }
            finally { broken.close(); }
            assertArrayEquals(previous,Files.readAllBytes(output.toPath()));
        } finally {
            task.close();
            for (File file : directory.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
