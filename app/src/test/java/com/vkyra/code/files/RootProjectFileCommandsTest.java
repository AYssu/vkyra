package com.vkyra.code.files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Executes the exact root backend commands in an ordinary host shell against disposable entries. */
public class RootProjectFileCommandsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private int execute(File source, String name, List<File> guarded) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", RootProjectFileCommands.operation(source, name, guarded))
                .redirectErrorStream(true).start();
        try (java.io.InputStream input = process.getInputStream()) {
            while (input.read() != -1) { /* Drain errors without exposing filenames in test logs. */ }
        }
        return process.waitFor();
    }

    @Test public void renameKeepsBytesAndQuotesShellMetacharacters() throws Exception {
        File source = temporary.newFile("source ' $(false) `false`\n");
        Files.write(source.toPath(), new byte[]{3, 1, 4});
        String name = "destination ' $(false) `false`";
        assertEquals(0, execute(source, name, Collections.emptyList()));
        assertFalse(source.exists());
        assertArrayEquals(new byte[]{3,1,4}, Files.readAllBytes(new File(temporary.getRoot(),name).toPath()));
    }

    @Test public void collisionsIncludingDanglingLinksKeepBothEntries() throws Exception {
        File source = temporary.newFile("source"); Files.write(source.toPath(),new byte[]{1});
        File destination = temporary.newFile("existing"); Files.write(destination.toPath(),new byte[]{9});
        assertEquals(RootProjectFileCommands.COLLISION,execute(source,"existing",Collections.emptyList()));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{9},Files.readAllBytes(destination.toPath()));
        File link = new File(temporary.getRoot(),"dangling");
        Files.createSymbolicLink(link.toPath(),new File(temporary.getRoot(),"missing").toPath());
        assertEquals(RootProjectFileCommands.COLLISION,execute(source,"dangling",Collections.emptyList()));
        assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(source.isFile());
    }

    @Test public void deletionDoesNotTraverseDirectoryOrDanglingSymlinks() throws Exception {
        File outside = temporary.newFolder("outside"); File marker = new File(outside,"marker");
        Files.write(marker.toPath(),new byte[]{7}); File selected = temporary.newFolder("selected");
        Files.createSymbolicLink(new File(selected,"link").toPath(),outside.toPath());
        Files.createSymbolicLink(new File(selected,"dangling").toPath(),new File(outside,"missing").toPath());
        assertEquals(0,execute(new File(selected,"."),null,Collections.emptyList()));
        assertFalse(selected.exists()); assertArrayEquals(new byte[]{7},Files.readAllBytes(marker.toPath()));
    }

    @Test public void selectedLinkWithTrailingSeparatorDeletesOnlyLink() throws Exception {
        File outside = temporary.newFolder("outside"); File marker = new File(outside,"marker");
        Files.write(marker.toPath(),new byte[]{8}); File link = new File(temporary.getRoot(),"link");
        Files.createSymbolicLink(link.toPath(),outside.toPath());
        assertEquals(0,execute(new File(link.getPath()+"/"),null,Collections.singletonList(marker)));
        assertFalse(Files.exists(link.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(new byte[]{8},Files.readAllBytes(marker.toPath()));
    }

    @Test public void physicalAliasOfOpenedBufferPreventsRenameAndDelete() throws Exception {
        File selected = temporary.newFolder("selected"); File marker = new File(selected,"marker");
        Files.write(marker.toPath(),new byte[]{2}); File alias = new File(temporary.getRoot(),"alias");
        Files.createSymbolicLink(alias.toPath(),selected.toPath());
        File opened = new File(alias,"marker");
        for (String name : new String[]{null,"renamed"})
            assertEquals(RootProjectFileCommands.PROTECTED,execute(selected,name,Collections.singletonList(opened)));
        assertArrayEquals(new byte[]{2},Files.readAllBytes(marker.toPath()));
    }

    @Test public void protectedPathsContainingNewlinesAreResolvedWithoutTrimming() throws Exception {
        File selected = temporary.newFolder("selected\n"); File marker = new File(selected,"marker\n");
        Files.write(marker.toPath(),new byte[]{6}); File alias = new File(temporary.getRoot(),"alias");
        Files.createSymbolicLink(alias.toPath(),selected.toPath());
        assertEquals(RootProjectFileCommands.PROTECTED,
                execute(selected,null,Collections.singletonList(new File(alias,marker.getName()))));
        assertTrue(marker.isFile());
    }

    @Test public void invalidNamesAndRootAreRejectedBeforeAnyCommand() throws Exception {
        File source = temporary.newFile("source");
        for (String name : new String[]{"../escape","one/two","..","bad\0name"}) {
            try { RootProjectFileCommands.operation(source,name,Collections.emptyList()); fail(name); }
            catch (IOException expected) { }
        }
        try { RootProjectFileCommands.operation(new File("/"),null,Collections.emptyList()); fail(); }
        catch (IOException expected) { }
        try { RootProjectFileCommands.operation(new File("/bad\0path"),null,Collections.emptyList()); fail(); }
        catch (IllegalArgumentException expected) { }
        assertTrue(source.exists());
    }

    @Test public void missingSourceOrUnresolvableProtectedPathFailsAndKeepsSource() throws Exception {
        assertNotEquals(0,execute(new File(temporary.getRoot(),"absent"),"new",Collections.emptyList()));
        assertFalse(new File(temporary.getRoot(),"new").exists());
        File source = temporary.newFile("source"); Files.write(source.toPath(),new byte[]{5});
        assertNotEquals(0,execute(source,null,Collections.singletonList(new File(temporary.getRoot(),"absent/deeper/file"))));
        assertArrayEquals(new byte[]{5},Files.readAllBytes(source.toPath()));
    }

    @Test public void shellExitFailureDoesNotTerminateCallingSession() throws Exception {
        File source = temporary.newFile("source"); temporary.newFile("destination");
        String command = RootProjectFileCommands.operation(source,"destination",Collections.emptyList());
        Process process = new ProcessBuilder("sh","-c",command+"; exit 19").start();
        assertEquals(19,process.waitFor()); assertTrue(source.exists());
    }

    @Test public void skippedOrFailedMoveNeverReportsSuccessOrChangesSource() throws Exception {
        File source=temporary.newFile("source"); Files.write(source.toPath(),new byte[]{4,2});
        String command=RootProjectFileCommands.operation(source,"destination",Collections.emptyList());
        // Model both mv -n's successful skip and an actual command failure.
        for(int exit:new int[]{0,23}) {
            Process process=new ProcessBuilder("sh","-c","mv() { return "+exit+"; }; "+command).start();
            assertNotEquals(0,process.waitFor());
            assertArrayEquals(new byte[]{4,2},Files.readAllBytes(source.toPath()));
            assertFalse(new File(temporary.getRoot(),"destination").exists());
        }
    }
}
