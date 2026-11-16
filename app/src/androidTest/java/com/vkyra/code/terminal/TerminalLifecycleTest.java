package com.vkyra.code.terminal;

import com.vkyra.code.toolchain.CompilerArguments;

import android.content.Context;
import android.content.Intent;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.terminal.TerminalSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TerminalLifecycleTest {
    @Test public void shortLivedProcessKeepsItsTrailingOutputWhenTheMainThreadIsBusy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<TerminalSessionViewModel> owner = new AtomicReference<>();
        AtomicReference<TerminalSession> session = new AtomicReference<>();
        String marker = "VKYRA_TRAILING_PTY_OUTPUT";
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                owner.set(new TerminalSessionViewModel());
                TerminalSession started = new TerminalSession("/system/bin/sh", context.getCacheDir().getPath(),
                        new String[]{"sh", "-c", "printf '%010000d%s\\n' 0 " + marker},
                        new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 1000, owner.get());
                session.set(started);
                started.initializeEmulator(80, 24);
                owner.get().own(started, false, "su -c");
                // Let the child exit while its reader is back-pressured by a busy UI thread.
                android.os.SystemClock.sleep(500);
            });
            long deadline = System.nanoTime() + 10_000_000_000L;
            AtomicReference<String> transcript = new AtomicReference<>("");
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        transcript.set(session.get().getEmulator().getScreen().getTranscriptText()));
                if (!session.get().isRunning()) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertFalse("short-lived command must finish", session.get().isRunning());
            assertEquals(0, session.get().getExitStatus());
            assertTrue("PTY output must be drained before appending the exit message; last screen: "
                    + transcript.get().substring(Math.max(0, transcript.get().length() - 300)),
                    transcript.get().contains(marker));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (owner.get() != null) owner.get().closeSession();
            });
        }
    }

    @Test public void closingReapedSessionDoesNotSignalAnotherProcessWhileOutputIsDraining() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<TerminalSessionViewModel> firstOwner = new AtomicReference<>();
        AtomicReference<TerminalSessionViewModel> otherOwner = new AtomicReference<>();
        AtomicReference<TerminalSession> other = new AtomicReference<>();
        java.lang.reflect.Field pid = TerminalSession.class.getDeclaredField("mShellPid");
        java.lang.reflect.Field exited = TerminalSession.class.getDeclaredField("mShellProcessExited");
        pid.setAccessible(true);
        exited.setAccessible(true);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                firstOwner.set(new TerminalSessionViewModel());
                otherOwner.set(new TerminalSessionViewModel());
                other.set(start(context, otherOwner.get()));
                TerminalSession first = new TerminalSession("/system/bin/sh", context.getCacheDir().getPath(),
                        new String[]{"sh", "-c", "printf '%010000d' 0"},
                        new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 1000, firstOwner.get());
                first.initializeEmulator(80, 24);
                firstOwner.get().own(first, false, "su -c");
                int originalPid = first.getPid();
                try {
                    // Keep output queued while the waiter reaps the child, then model PID reuse
                    // with another process owned solely by this test (never a shared device PID).
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    while (!exited.getBoolean(first) && System.nanoTime() < deadline) android.os.SystemClock.sleep(10);
                    assertTrue("the child must have exited before checking cancellation", exited.getBoolean(first));
                    assertTrue("display completion must still wait for trailing output", first.isRunning());
                    pid.setInt(first, other.get().getPid());
                    first.finishIfRunning();
                } catch (IllegalAccessException error) { throw new AssertionError(error); }
                finally {
                    try { pid.setInt(first, originalPid); }
                    catch (IllegalAccessException error) { throw new AssertionError(error); }
                }
            });
            Thread.sleep(100);
            assertNotNull("closing a reaped session must not signal a reused PID", ProcessOwnership.read(other.get().getPid()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (firstOwner.get() != null) firstOwner.get().closeSession();
                if (otherOwner.get() != null) otherOwner.get().closeSession();
            });
        }
    }

    @Test public void recreationKeepsTheSameShellAndItsEnvironment() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File marker = File.createTempFile("vkyra-terminal-state", ".txt", context.getCacheDir());
        Intent intent = new Intent(context, TerminalActivity.class).putExtra(TerminalActivity.RUN_ARGS, "snapshot");
        try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent)) {
            TerminalSession original = waitForSession(scenario);
            int pid = original.getPid();
            scenario.onActivity(activity -> original.write("VKYRA_TEST_STATE=preserved; printf ready > "
                    + CompilerArguments.shellQuote(marker.getPath()) + "\n"));
            waitForContent(marker, "ready");
            scenario.recreate();
            TerminalSession restored = waitForSession(scenario);
            assertSame(original, restored);
            assertEquals(pid, restored.getPid());
            scenario.onActivity(activity -> restored.write("printf '%s' \"$VKYRA_TEST_STATE\" > "
                    + CompilerArguments.shellQuote(marker.getPath()) + "\n"));
            waitForContent(marker, "preserved");
        } finally { marker.delete(); }
    }

    @Test public void closingOnePtyTerminatesItsChildrenAndLeavesOtherSessionRunning() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<TerminalSessionViewModel> firstOwner = new AtomicReference<>();
        AtomicReference<TerminalSessionViewModel> secondOwner = new AtomicReference<>();
        AtomicReference<TerminalSession> first = new AtomicReference<>();
        AtomicReference<TerminalSession> second = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            firstOwner.set(new TerminalSessionViewModel()); secondOwner.set(new TerminalSessionViewModel());
            first.set(start(context, firstOwner.get())); second.set(start(context, secondOwner.get()));
        });
        int firstId = first.get().getPid();
        int secondId = second.get().getPid();
        try {
            long ready = System.nanoTime() + 5_000_000_000L;
            while (ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), firstId).isEmpty() && System.nanoTime() < ready) Thread.sleep(20);
            assertFalse(ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), firstId).isEmpty());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> firstOwner.get().closeSession());
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), firstId).isEmpty() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue("closed PTY has no surviving process group", ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), firstId).isEmpty());
            assertTrue("independent PTY still runs", second.get().isRunning());
            assertNotNull(ProcessOwnership.read(secondId));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { firstOwner.get().closeSession(); secondOwner.get().closeSession(); });
        }
    }

    private static TerminalSession start(Context context, TerminalSessionViewModel owner) {
        TerminalSession session = new TerminalSession("/system/bin/sh", context.getCacheDir().getPath(),
                new String[]{"sh", "-c", "sleep 60 & wait"},
                new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 1000, owner);
        session.initializeEmulator(80, 24);
        owner.own(session, false, "su -c");
        return session;
    }
    private static TerminalSession waitForSession(ActivityScenario<TerminalActivity> scenario) throws Exception {
        AtomicReference<TerminalSession> session = new AtomicReference<>();
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            scenario.onActivity(activity -> session.set(new ViewModelProvider(activity).get(TerminalSessionViewModel.class).getSession()));
            if (session.get() != null && session.get().isRunning()) return session.get();
            Thread.sleep(50);
        }
        fail("terminal session did not initialize"); return null;
    }
    private static void waitForContent(File file, String expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (expected.equals(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))) return;
            Thread.sleep(25);
        }
        assertEquals(expected, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
