package com.vkyra.code.update;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

public class VersionCheckerTest {
    @Test public void disabledRepositoryCompletesWithoutAnyRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Result result = new Result();
            try (VersionChecker checker = new VersionChecker(null, new OkHttpClient(), null, "1.0.0", "x86_64", Runnable::run)) {
                checker.checkVersion(result);
                assertTrue(result.done.await(1, TimeUnit.SECONDS));
                assertFalse(result.newer); assertNull(result.info); assertNull(result.error);
                assertEquals(0, server.getRequestCount());
            }
        }
    }
    @Test public void emptyAndFailedResponsesAlwaysDeliverTerminalCallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]"));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setBody("{broken"));
            try (VersionChecker checker = checker(server, Runnable::run)) {
                for (int i = 0; i < 3; i++) {
                    Result result = new Result(); checker.checkVersion(result);
                    assertTrue(result.done.await(3, TimeUnit.SECONDS));
                    if (i == 0) { assertNull(result.error); assertNull(result.info); }
                    else assertNotNull(result.error);
                }
            }
        }
    }
    @Test public void realHttpResponseSelectsAbiAndSemanticVersion() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("["+UpdateReleaseSelectorTest.release("1.10.0", false, false, "app_x86_64.apk")+"]"));
            try (VersionChecker checker = checker(server, Runnable::run)) {
                Result result = new Result(); checker.checkVersion(result);
                assertTrue(result.done.await(3, TimeUnit.SECONDS));
                assertTrue(result.newer); assertEquals("1.10.0", result.info.version);
            }
        }
    }
    @Test public void cancelAndCloseSuppressAlreadyQueuedCallbacks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
            VersionChecker checker = new VersionChecker(null, new OkHttpClient(), null, "1.0.0", "x86_64", callbacks::add);
            Result first = new Result(); checker.checkVersion(first); Runnable stale = callbacks.poll(1, TimeUnit.SECONDS);
            checker.cancel(); stale.run(); assertEquals(1, first.done.getCount());
            Result second = new Result(); checker.checkVersion(second); stale = callbacks.poll(1, TimeUnit.SECONDS);
            checker.close(); stale.run(); assertEquals(1, second.done.getCount());
            checker.checkVersion(new Result()); assertTrue(callbacks.isEmpty());
        }
    }
    @Test public void cancellingBlockedHttpAbortsCallAndAllowsFreshCheck() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch aborted = new CountDownLatch(1);
            OkHttpClient client = new OkHttpClient.Builder().eventListener(new okhttp3.EventListener() {
                @Override public void callFailed(okhttp3.Call call, java.io.IOException error) { aborted.countDown(); }
            }).build();
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            try (VersionChecker checker = new VersionChecker(null, client, server.url("/releases"), "1.0.0", "x86_64", Runnable::run)) {
                Result cancelled = new Result(); checker.checkVersion(cancelled);
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
                checker.cancel(); assertTrue(aborted.await(3, TimeUnit.SECONDS));
                assertEquals(1, cancelled.done.getCount());
                server.enqueue(new MockResponse().setBody("[]"));
                Result recovery = new Result(); checker.checkVersion(recovery);
                assertTrue(recovery.done.await(3, TimeUnit.SECONDS)); assertNull(recovery.error);
            }
        }
    }
    private static VersionChecker checker(MockWebServer server, java.util.concurrent.Executor callbacks) {
        return new VersionChecker(null, new OkHttpClient(), server.url("/releases"), "1.9.9", "x86_64", callbacks);
    }
    static final class Result implements VersionChecker.VersionCheckCallback {
        final CountDownLatch done = new CountDownLatch(1); boolean newer; VersionChecker.ReleaseInfo info; String error;
        public void onVersionCheckComplete(boolean newer, VersionChecker.ReleaseInfo info, String current) { this.newer = newer; this.info = info; done.countDown(); }
        public void onVersionCheckError(String error) { this.error = error; done.countDown(); }
    }
}
