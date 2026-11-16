package com.vkyra.code.update;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class UpdateLifecycleInstrumentedTest {
    @Test public void disabledCheckerCompletesOnMainAndCloseSuppressesQueuedDelivery() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean main = new AtomicBoolean(), stale = new AtomicBoolean();
        VersionChecker.VersionCheckCallback callback = new VersionChecker.VersionCheckCallback() {
            public void onVersionCheckComplete(boolean newer, VersionChecker.ReleaseInfo info, String current) {
                main.set(Looper.myLooper() == Looper.getMainLooper()); assertFalse(newer); assertNull(info); delivered.countDown();
            }
            public void onVersionCheckError(String error) { throw new AssertionError(error); }
        };
        try (VersionChecker checker = new VersionChecker(context, new OkHttpClient(), null, "1.0.0", "x86_64",
                runnable -> new android.os.Handler(Looper.getMainLooper()).post(runnable))) {
            checker.checkVersion(callback);
            assertTrue(delivered.await(3, TimeUnit.SECONDS)); assertTrue(main.get());
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            VersionChecker checker = new VersionChecker(context, new OkHttpClient(), null, "1.0.0", "x86_64",
                    runnable -> new android.os.Handler(Looper.getMainLooper()).post(runnable));
            checker.checkVersion(new VersionChecker.VersionCheckCallback() {
                public void onVersionCheckComplete(boolean newer, VersionChecker.ReleaseInfo info, String current) { stale.set(true); }
                public void onVersionCheckError(String error) { stale.set(true); }
            });
            checker.close();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertFalse(stale.get());
    }
    @Test public void insecureDownloadErrorsOnMainAndCloseSuppressesQueuedError() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean main = new AtomicBoolean(), stale = new AtomicBoolean();
        try (ApkDownloadManager manager = new ApkDownloadManager(context)) {
            manager.downloadApk("http://example.invalid/app.apk", new NoopCallback() {
                @Override public void onDownloadError(String error) { main.set(Looper.myLooper() == Looper.getMainLooper()); delivered.countDown(); }
            });
            assertTrue(delivered.await(3, TimeUnit.SECONDS)); assertTrue(main.get()); assertFalse(manager.isDownloading());
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ApkDownloadManager manager = new ApkDownloadManager(context);
            manager.downloadApk("http://example.invalid/app.apk", new NoopCallback() {
                @Override public void onDownloadError(String error) { stale.set(true); }
            });
            manager.close();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertFalse(stale.get());
    }
    private static class NoopCallback implements ApkDownloadManager.DownloadCallback {
        public void onProgress(int progress, long downloaded, long total) { }
        public void onDownloadComplete(java.io.File file) { }
        public void onDownloadError(String error) { }
        public void onInstallError(String error) { }
    }
}
