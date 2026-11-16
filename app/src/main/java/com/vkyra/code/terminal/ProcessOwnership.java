package com.vkyra.code.terminal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Linux process identities. Names are never used to decide ownership. */
public final class ProcessOwnership {
    public static final class Identity {
        public final int pid;
        public final int parentPid;
        public final int processGroup;
        public final int sessionId;
        public final long startTicks;
        Identity(int pid, int parentPid, int processGroup, int sessionId, long startTicks) {
            this.pid = pid; this.parentPid = parentPid; this.processGroup = processGroup;
            this.sessionId = sessionId; this.startTicks = startTicks;
        }
        public boolean sameProcess(Identity other) {
            return other != null && pid == other.pid && startTicks == other.startTicks;
        }
    }
    private ProcessOwnership() { }

    static Identity parseStat(String stat) {
        try {
            int open = stat.indexOf('(');
            int close = stat.lastIndexOf(')');
            if (open < 1 || close < open) return null;
            int pid = Integer.parseInt(stat.substring(0, open).trim());
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            if (fields.length < 20 || pid <= 1) return null;
            return new Identity(pid, Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), Long.parseLong(fields[19]));
        } catch (RuntimeException invalid) { return null; }
    }

    public static Identity read(int pid) {
        if (pid <= 1) return null;
        try {
            return parseStat(new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8));
        } catch (IOException | SecurityException inaccessible) { return null; }
    }

    public static List<Identity> snapshot() {
        List<Identity> result = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return result;
        for (File entry : entries) {
            try {
                Identity identity = read(Integer.parseInt(entry.getName()));
                if (identity != null) result.add(identity);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    public static Map<Integer, Identity> sessionGroups(List<Identity> processes, int sessionId) {
        Map<Integer, Identity> groups = new LinkedHashMap<>();
        if (sessionId <= 1) return groups;
        for (Identity identity : processes) {
            if (identity.sessionId == sessionId && identity.processGroup > 1) groups.put(identity.processGroup, identity);
        }
        return groups;
    }

    public static List<Identity> descendants(List<Identity> processes, Identity root) {
        List<Identity> result = new ArrayList<>();
        if (root == null) return result;
        Set<Integer> owned = new LinkedHashSet<>();
        boolean valid = false;
        for (Identity identity : processes) if (root.sameProcess(identity)) valid = true;
        if (!valid) return result;
        owned.add(root.pid);
        boolean changed;
        do {
            changed = false;
            for (Identity identity : processes) {
                if (owned.contains(identity.parentPid) && owned.add(identity.pid)) { result.add(identity); changed = true; }
            }
        } while (changed);
        java.util.Collections.reverse(result);
        return result;
    }
}
