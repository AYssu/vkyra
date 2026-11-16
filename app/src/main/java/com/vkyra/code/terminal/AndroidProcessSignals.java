package com.vkyra.code.terminal;

import com.vkyra.code.Log;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Signals only process identities captured from this owned PTY session. Never searches names. */
final class AndroidProcessSignals {
    private AndroidProcessSignals() { }

    static void terminateSession(int sessionId, long rootStartTicks, boolean allowRoot, String customSu) {
        ProcessOwnership.Identity root = ProcessOwnership.read(sessionId);
        if (root != null && root.startTicks != rootStartTicks) return;
        Map<Integer, ProcessOwnership.Identity> groups = ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), sessionId);
        for (Map.Entry<Integer, ProcessOwnership.Identity> entry : groups.entrySet()) {
            ProcessOwnership.Identity witness = entry.getValue();
            ProcessOwnership.Identity current = ProcessOwnership.read(witness.pid);
            if (!witness.sameProcess(current) || current.sessionId != sessionId || current.processGroup != entry.getKey()) continue;
            // Signal mixed-UID groups with explicitly requested root before the unprivileged
            // witness dies; killpg can otherwise succeed while leaving a root child alive.
            if (allowRoot && customSu != null && !customSu.trim().isEmpty()) {
                terminateWithExplicitRoot(entry.getKey(), witness, customSu);
            }
            current = ProcessOwnership.read(witness.pid);
            if (!witness.sameProcess(current) || current.sessionId != sessionId || current.processGroup != entry.getKey()) continue;
            try { Os.kill(-entry.getKey(), OsConstants.SIGKILL); }
            catch (ErrnoException denied) { android.util.Log.w("VkyraTerminal", "Could not signal an owned PTY group", denied); }
        }
    }

    private static void terminateWithExplicitRoot(int group, ProcessOwnership.Identity witness, String customSu) {
        // Recheck PID birth and session in the privileged shell, avoiding stale PID/group reuse.
        String script = "s=$(cat /proc/" + witness.pid + "/stat 2>/dev/null) || exit; "
                + "rest=${s##*) }; set -- $rest; "
                + "[ \"$4\" = \"" + witness.sessionId + "\" ] && "
                + "[ \"$3\" = \"" + group + "\" ] && "
                + "[ \"${20}\" = \"" + witness.startTicks + "\" ] && kill -9 -- -" + group;
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", customSu + " " + shellQuote(script))
                    .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception failure) {
            android.util.Log.w("VkyraTerminal", "Could not terminate an owned privileged process group", failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
