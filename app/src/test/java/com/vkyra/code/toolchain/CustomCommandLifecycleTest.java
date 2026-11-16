package com.vkyra.code.toolchain;

import android.os.Looper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 26)
@LooperMode(LooperMode.Mode.PAUSED)
public class CustomCommandLifecycleTest {
    private static final class Page implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        @Override public Lifecycle getLifecycle() { return lifecycle; }
        Page() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); }
    }

    @Test public void destructionCancelsActualProcessCleansUiOnceAndDropsQueuedCallback() throws Exception {
        Page page = new Page();
        AtomicInteger cleanup = new AtomicInteger(), callbacks = new AtomicInteger();
        File directory = Files.createTempDirectory("custom-command-page-").toFile();
        CustomCommandTasks.OwnedTask task = CustomCommandTasks.start(page,
                new CustomCommandTask.Request("/bin/sh",
                        "printf ready > ready; sleep 2; printf forbidden > forbidden", directory,
                        5000, "source", "output", "run"),
                result -> callbacks.incrementAndGet(), cleanup::incrementAndGet);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!new File(directory, "ready").exists() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(new File(directory, "ready").exists());
        page.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        assertEquals(1, cleanup.get());
        task.whenClosed().get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(task.isActive());
        assertFalse(new File(directory, "forbidden").exists());
        assertEquals(0, callbacks.get());
        task.cancel();
        assertEquals(1, cleanup.get());
        assertEquals(0, page.lifecycle.getObserverCount());
    }

    @Test public void completionCleansUiBeforeMainThreadDeliveryAndReleasesObserver() throws Exception {
        Page page = new Page();
        AtomicInteger cleanup = new AtomicInteger(), callbacks = new AtomicInteger();
        CustomCommandTasks.OwnedTask task = CustomCommandTasks.start(page,
                new CustomCommandTask.Request("/bin/sh", "printf success", null,
                        5000, "source", "output", "run"), result -> {
                    assertSame(Looper.getMainLooper(), Looper.myLooper());
                    assertEquals(1, cleanup.get());
                    assertTrue(result.errorMessage, result.success);
                    callbacks.incrementAndGet();
                }, cleanup::incrementAndGet);
        task.whenClosed().get(5, TimeUnit.SECONDS);
        assertEquals(0, callbacks.get());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, cleanup.get());
        assertEquals(1, callbacks.get());
        assertEquals(0, page.lifecycle.getObserverCount());
        page.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        assertFalse(task.isActive());
        task.cancel();
        assertEquals(1, cleanup.get());
    }

    @Test public void destructionAfterProcessExitStillSuppressesQueuedDelivery() throws Exception {
        Page page = new Page();
        AtomicInteger cleanup = new AtomicInteger(), callbacks = new AtomicInteger();
        CustomCommandTasks.OwnedTask task = CustomCommandTasks.start(page,
                new CustomCommandTask.Request("/bin/sh", "printf success", null,
                        5000, null, null, null), result -> callbacks.incrementAndGet(), cleanup::incrementAndGet);
        task.whenClosed().get(5, TimeUnit.SECONDS);
        page.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, cleanup.get());
        assertEquals(0, callbacks.get());
        assertEquals(0, page.lifecycle.getObserverCount());
    }
}
