package com.vkyra.code.toolchain;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CompilerArtifactsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void plannedPathsAreUniqueAndDoNotTouchDisk() {
        File files = new File(temporary.getRoot(), "not-created");
        assertNotEquals(CompilerArtifacts.plan(files), CompilerArtifacts.plan(files));
        assertFalse(files.exists());
    }

    @Test public void failedAndCancelledBuildsLeaveNoOwnedDirectoryOrOldBinaryChanges() throws Exception {
        File files = temporary.newFolder();
        File old = new File(files, "tmp/a.out");
        assertTrue(old.getParentFile().mkdirs());
        Files.write(old.toPath(), "old binary".getBytes(StandardCharsets.UTF_8));
        File owned = CompilerArtifacts.begin(files, CompilerArtifacts.plan(files));
        Files.write(new File(owned, ".vkyra-build-partial.out").toPath(), new byte[]{1});
        CompilerArtifacts.finish(owned, false);
        assertFalse(owned.exists());
        assertEquals("old binary", new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void successesAreBoundedWhileInFlightBuildAndUserDirectoryArePreserved() throws Exception {
        File files = temporary.newFolder();
        File inFlight = CompilerArtifacts.begin(files, CompilerArtifacts.plan(files));
        File user = new File(files, "tmp/build-user-project");
        assertTrue(user.mkdirs());
        Files.write(new File(user, "source.cpp").toPath(), new byte[]{1});
        List<File> completed = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            File output = CompilerArtifacts.begin(files, CompilerArtifacts.plan(files));
            Files.write(new File(output, "a.out").toPath(), new byte[]{1});
            CompilerArtifacts.finish(output, true);
            completed.add(output);
            assertTrue(inFlight.exists());
        }
        assertEquals(CompilerArtifacts.RETAINED_BUILDS, completed.stream().filter(File::exists).count());
        assertTrue(completed.get(completed.size() - 1).exists());
        assertTrue(new File(user, "source.cpp").exists());
        CompilerArtifacts.finish(inFlight, false);
    }

    @Test public void previousTemporaryDirectoriesArePrunedButSymlinkTargetsRemainUntouched() throws Exception {
        File files = temporary.newFolder();
        File tmp = new File(files, "tmp"); assertTrue(tmp.mkdir());
        for (int i = 100000; i < 100020; i++) {
            File legacy = new File(tmp, "build-" + i); assertTrue(legacy.mkdir());
            Files.write(new File(legacy, "a.out").toPath(), new byte[]{1});
        }
        File external = temporary.newFolder();
        File preserved = new File(external, "keep"); Files.write(preserved.toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(tmp, "build-999999").toPath(), external.toPath());
        File current = CompilerArtifacts.begin(files, CompilerArtifacts.plan(files));
        CompilerArtifacts.finish(current, true);
        File[] legacy = tmp.listFiles(file -> file.getName().matches("build-[0-9]{6}") && !Files.isSymbolicLink(file.toPath()));
        assertNotNull(legacy);
        assertEquals(CompilerArtifacts.RETAINED_BUILDS - 1, legacy.length);
        assertTrue(preserved.isFile());
    }
}
