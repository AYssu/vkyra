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
