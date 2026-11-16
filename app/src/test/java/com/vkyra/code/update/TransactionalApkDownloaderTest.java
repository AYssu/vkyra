package com.vkyra.code.update;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TransactionalApkDownloaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void publishesOnlyAfterValidationAndCleansStaging() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            File target = new File(temp.getRoot(), "update.apk"); Files.writeString(target.toPath(), "old");
            server.enqueue(new MockResponse().setBody("new signed apk"));
            TransactionalApkDownloader.download(call(server), target, () -> false, staged -> {
                assertEquals("old", Files.readString(target.toPath())); assertEquals("new signed apk", Files.readString(staged.toPath()));
            }, (p,d,t) -> { });
            assertEquals("new signed apk", Files.readString(target.toPath())); assertEquals(1, temp.getRoot().list().length);
        }
    }
    @Test public void verificationFailurePreservesPreviousArtifact() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            File target = new File(temp.getRoot(), "update.apk"); Files.writeString(target.toPath(), "old signed apk");
            server.enqueue(new MockResponse().setBody("attacker apk"));
            try { TransactionalApkDownloader.download(call(server), target, () -> false, f -> { throw new IOException("wrong signer"); }, (p,d,t)->{}); fail(); }
            catch (IOException expected) { }
            assertEquals("old signed apk", Files.readString(target.toPath())); assertEquals(1, temp.getRoot().list().length);
        }
    }
    @Test public void disconnectAndCancelNeverPublishPartialApk() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            File target = new File(temp.getRoot(), "update.apk");
            server.enqueue(new MockResponse().setBody("x".repeat(65536)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            try { TransactionalApkDownloader.download(call(server), target, () -> false, f->{}, (p,d,t)->{}); fail(); } catch (IOException expected) { }
            assertFalse(target.exists()); assertEquals(0, temp.getRoot().list().length);
            server.enqueue(new MockResponse().setBody("x".repeat(65536)));
            AtomicBoolean cancelled = new AtomicBoolean();
            try { TransactionalApkDownloader.download(call(server), target, cancelled::get, f->{}, (p,d,t)->cancelled.set(true)); fail(); }
            catch (IOException expected) { }
            assertFalse(target.exists()); assertEquals(0, temp.getRoot().list().length);
        }
    }
    @Test public void httpErrorEmptyBodyAndOversizeAreRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (MockResponse response : new MockResponse[]{new MockResponse().setResponseCode(404), new MockResponse().setBody(""),
                    new MockResponse().setHeader("Content-Length", TransactionalApkDownloader.MAX_APK_BYTES+1)}) {
                server.enqueue(response); File target = new File(temp.getRoot(), "update.apk");
                try { TransactionalApkDownloader.download(call(server), target, ()->false, f->{}, (p,d,t)->{}); fail(); } catch (IOException expected) { }
                assertFalse(target.exists()); assertEquals(0, temp.getRoot().list().length);
            }
        }
    }
    private okhttp3.Call call(MockWebServer server) { return new OkHttpClient().newCall(new Request.Builder().url(server.url("/app.apk")).build()); }
}
