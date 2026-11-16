package com.vkyra.code.terminal;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ProcessOwnershipTest {
    @Test public void statParserHandlesSpacesAndClosingParenthesesInCommandName() {
        ProcessOwnership.Identity process = ProcessOwnership.parseStat(stat(410, "a.out (test)", 20, 410, 410, 999));
        assertNotNull(process);
        assertEquals(410, process.pid);
        assertEquals(20, process.parentPid);
        assertEquals(410, process.processGroup);
        assertEquals(410, process.sessionId);
        assertEquals(999, process.startTicks);
    }
    @Test public void processNameNeverMakesAnotherSessionOwned() {
        ProcessOwnership.Identity a = id(410, 1, 410, 410, 1);
        ProcessOwnership.Identity child = id(411, 410, 411, 410, 2);
        ProcessOwnership.Identity other = id(510, 1, 510, 510, 3);
        Map<Integer, ProcessOwnership.Identity> groups = ProcessOwnership.sessionGroups(Arrays.asList(a, child, other), 410);
        assertEquals(2, groups.size());
        assertTrue(groups.containsKey(410));
        assertTrue(groups.containsKey(411));
        assertFalse(groups.containsKey(510));
    }
    @Test public void descendantsRequireOriginalRootBirthAndExcludeOtherTrees() {
        ProcessOwnership.Identity root = id(410, 1, 4, 4, 1);
        ProcessOwnership.Identity child = id(411, 410, 4, 4, 2);
        ProcessOwnership.Identity grandchild = id(412, 411, 4, 4, 3);
        ProcessOwnership.Identity other = id(510, 1, 4, 4, 4);
        List<ProcessOwnership.Identity> tree = ProcessOwnership.descendants(Arrays.asList(root, child, grandchild, other), root);
        assertEquals(2, tree.size());
        assertEquals(412, tree.get(0).pid);
        assertFalse(root.sameProcess(id(410, 1, 4, 4, 99)));
        assertTrue(ProcessOwnership.descendants(Arrays.asList(id(410, 1, 4, 4, 99), child), root).isEmpty());
    }
    @Test public void invalidStatAndSessionCannotSelectProcesses() {
        assertNull(ProcessOwnership.parseStat("not a process"));
        assertTrue(ProcessOwnership.sessionGroups(Arrays.asList(id(3, 2, 2, 0, 4)), 0).isEmpty());
    }
    private static ProcessOwnership.Identity id(int pid, int parent, int group, int session, long ticks) {
        return ProcessOwnership.parseStat(stat(pid, "a.out", parent, group, session, ticks));
    }
    private static String stat(int pid, String name, int parent, int group, int session, long ticks) {
        String[] tail = new String[20];
        Arrays.fill(tail, "0"); tail[0] = "S"; tail[1] = "" + parent; tail[2] = "" + group;
        tail[3] = "" + session; tail[19] = "" + ticks;
        return pid + " (" + name + ") " + String.join(" ", tail);
    }
}
