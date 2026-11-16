package com.vkyra.code.installer;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class InstallJobTest {
    @Test public void cancellationClosesBlockedStreamAndSuppressesQueuedPageCallbacks() throws Exception {
        Queue<Runnable> delivery = new ConcurrentLinkedQueue<>();
        CountDownLatch blocked = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        InstallJob job = new InstallJob((token, progress) -> {
            InputStream stream = new InputStream() {
                @Override public int read() throws IOException {
                    blocked.countDown();
                    try { released.await(); } catch (InterruptedException ignored) { }
                    while (released.getCount() != 0) Thread.yield();
                    throw new IOException("closed");
                }
                @Override public void close() { released.countDown(); }
            };
            try (InputStream owned = stream) {
                token.attach(owned);
                progress.file(1, -1, "queued callback");
                owned.read();
            } finally { token.detach(stream); }
            return 1;
        }, listener(callbacks), delivery::add);
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        job.cancel();
        job.whenClosed().get(2, TimeUnit.SECONDS);
        assertEquals(0, released.getCount());
        delivery.forEach(Runnable::run);
        assertEquals(0, callbacks.get());
    }

    @Test public void ordinarySuccessFailureAndRepeatedJobsReleaseWorkers() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            final boolean fail = (i & 1) == 0;
            InstallJob job = new InstallJob((token, progress) -> {
                if (fail) throw new IOException("injected");
                return 1;
            }, listener(callbacks), Runnable::run);
            job.whenClosed().get(2, TimeUnit.SECONDS);
        }
        assertEquals(12, callbacks.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (workers() != 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(0, workers());
    }

    private static InstallJob.Listener listener(AtomicInteger callbacks) {
        return new InstallJob.Listener() {
            @Override public void progress(int current, int total, String name) { callbacks.incrementAndGet(); }
            @Override public void success(int count) { callbacks.incrementAndGet(); }
            @Override public void error(String error) { callbacks.incrementAndGet(); }
        };
    }
    private static long workers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("vkyra-toolchain-install")).count();
    }
}
