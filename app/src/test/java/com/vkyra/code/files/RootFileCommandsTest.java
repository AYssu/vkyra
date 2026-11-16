package com.vkyra.code.files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RootFileCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rootCommitQuotesPathsAndPreservesMetadata() throws Exception {
        File target = new File(temporary.getRoot(), "target '$()` file\nname.cpp");
        File staging = new File(temporary.getRoot(), "staging '$()` file.cpp");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(staging.toPath(), "new".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-----"));
        Process process = new ProcessBuilder("sh", "-c", RootFileCommands.commit(staging.getPath(),
                target.getPath(), true)).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertFalse(staging.exists());
        assertEquals("new", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals("rwxr-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())));
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void failedRootCommitKeepsOriginalFile() throws Exception {
        File target = temporary.newFile("safe.cpp");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        File absentStaging = new File(temporary.getRoot(), "missing.tmp");
        Process process = new ProcessBuilder("sh", "-c", RootFileCommands.commit(absentStaging.getPath(),
                target.getPath(), true)).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, process.exitValue());
        assertEquals("old", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void nulCannotBeEmbeddedInShellArgument() {
        try {
            RootFileCommands.quote("bad\0path");
            fail("NUL path accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("NUL"));
        }
    }
}
