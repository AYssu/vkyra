package com.vkyra.code.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PluginInstallTransactionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rejectsTraversalAndReservedIds() throws Exception {
        File root = temp.newFolder();
        for (String id : new String[]{"../clang", "/data", "..", ".", ".hidden", "x/y", "x\\y", "", "中文", "a\n"}) {
            assertFalse(id, PluginInstallTransaction.isValidPluginId(id));
            try { PluginInstallTransaction.resolvePluginDirectory(root, id); fail(id); }
            catch (IOException expected) { }
        }
        assertTrue(PluginInstallTransaction.isValidPluginId("clang-18.1_x86"));
    }

    @Test public void failedProcessCleanupBlocksRollbackAndPreservesOldBackup() throws Exception {
        File root = temp.newFolder();
        write(new File(root, "sample/old"), "original");
        PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample");
        write(new File(tx.getStagingDirectory(), "new"), "replacement");
        tx.activate(); tx.blockRecovery();
        try { tx.rollback(); fail("Unsafe rollback must be refused"); } catch (IOException expected) { }
        File[] journals = new File(root, ".install-transactions").listFiles();
        assertNotNull(journals); assertEquals(1, journals.length);
        assertEquals("original", read(new File(journals[0], "backup/old")));
        assertEquals("replacement", read(new File(root, "sample/new")));
        try { PluginInstallTransaction.recoverInterruptedInstalls(root); fail("Unsafe recovery must be refused"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsSymlinkAsTarget() throws Exception {
        File root = temp.newFolder();
        File outside = temp.newFolder();
        Files.createSymbolicLink(new File(root, "bad").toPath(), outside.toPath());
        try { PluginInstallTransaction.begin(root, "bad"); fail(); }
        catch (IOException expected) { }
        assertTrue(outside.exists());
    }

    @Test public void rollbackBeforeActivationKeepsOriginal() throws Exception {
        File root = temp.newFolder();
        write(new File(root, "sample/old"), "old");
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample")) {
            write(new File(tx.getStagingDirectory(), "new"), "new");
        }
        assertEquals("old", read(new File(root, "sample/old")));
        assertFalse(new File(root, "sample/new").exists());
    }

    @Test public void scriptFailureRestoresPreviousVersion() throws Exception {
        File root = temp.newFolder();
        write(new File(root, "sample/old"), "original");
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample")) {
            write(new File(tx.getStagingDirectory(), "new"), "replacement");
            tx.activate();
            assertTrue(new File(root, "sample/new").exists());
            tx.rollback();
        }
        assertEquals("original", read(new File(root, "sample/old")));
        assertFalse(new File(root, "sample/new").exists());
    }

    @Test public void commitReplacesOldVersionAndKeepsFinalPath() throws Exception {
        File root = temp.newFolder();
        write(new File(root, "sample/old"), "old");
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample")) {
            write(new File(tx.getStagingDirectory(), "new"), "new");
            tx.activate();
            assertEquals(new File(root, "sample").getCanonicalFile(), tx.getTargetDirectory());
            tx.commit();
        }
        PluginInstallTransaction.recoverInterruptedInstalls(root);
        assertEquals("new", read(new File(root, "sample/new")));
        assertFalse(new File(root, "sample/old").exists());
    }

    @Test public void samePluginCannotInstallConcurrently() throws Exception {
        File root = temp.newFolder();
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample")) {
            try { PluginInstallTransaction.begin(root, "sample"); fail(); }
            catch (IOException expected) { }
            PluginInstallTransaction.recoverInterruptedInstalls(root);
            assertTrue(tx.getStagingDirectory().exists());
        }
    }

    @Test public void failedActivationRestoresPreviousVersion() throws Exception {
        File root = temp.newFolder();
        write(new File(root, "sample/old"), "old");
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "sample")) {
            PluginInstallTransaction.deleteTree(tx.getStagingDirectory());
            try { tx.activate(); fail(); } catch (IOException expected) { }
        }
        assertEquals("old", read(new File(root, "sample/old")));
    }

    @Test public void crashRecoveryRestoresBackup() throws Exception {
        File root = temp.newFolder();
        File journal = journal(root);
        write(new File(journal, "activating"), "");
        write(new File(journal, "backup/old"), "old");
        write(new File(root, "sample/partial"), "broken");
        PluginInstallTransaction.recoverInterruptedInstalls(root);
        assertEquals("old", read(new File(root, "sample/old")));
        assertFalse(new File(root, "sample/partial").exists());
    }

    @Test public void crashRecoveryAfterRestoreDoesNotDeleteRestoredPlugin() throws Exception {
        File root = temp.newFolder();
        File journal = journal(root);
        write(new File(journal, "activating"), "");
        write(new File(journal, "restoring"), "");
        write(new File(root, "sample/old"), "old");
        PluginInstallTransaction.recoverInterruptedInstalls(root);
        assertEquals("old", read(new File(root, "sample/old")));
    }

    @Test public void interruptedNewInstallRemovedAndCommittedInstallKept() throws Exception {
        File root = temp.newFolder();
        File journal = journal(root);
        write(new File(journal, "activating"), "");
        write(new File(root, "sample/partial"), "broken");
        PluginInstallTransaction.recoverInterruptedInstalls(root);
        assertFalse(new File(root, "sample").exists());
        journal = journal(root);
        write(new File(journal, "activating"), "");
        write(new File(journal, "committed"), "");
        write(new File(root, "sample/new"), "new");
        PluginInstallTransaction.recoverInterruptedInstalls(root);
        assertEquals("new", read(new File(root, "sample/new")));
    }

    @Test public void deleteTreeDeletesLinkWithoutTouchingItsTarget() throws Exception {
        File root = temp.newFolder();
        File outside = temp.newFolder();
        write(new File(outside, "precious"), "keep");
        Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        PluginInstallTransaction.deleteTree(root);
        assertEquals("keep", read(new File(outside, "precious")));
    }

    @Test public void metadataReplacementDoesNotFollowExistingSymlink() throws Exception {
        File directory = temp.newFolder();
        File outside = temp.newFile();
        Files.write(outside.toPath(), "precious".getBytes(StandardCharsets.UTF_8));
        File metadata = new File(directory, ".abi");
        Files.createSymbolicLink(metadata.toPath(), outside.toPath());
        PluginInstallTransaction.writeMetadata(metadata, "x86_64".getBytes(StandardCharsets.UTF_8));
        assertEquals("x86_64", read(metadata));
        assertEquals("precious", read(outside));
        assertFalse(Files.isSymbolicLink(metadata.toPath()));
    }

    private File journal(File root) throws IOException {
        File dir = new File(root, ".install-transactions/test-transaction");
        write(new File(dir, "id"), "sample");
        return dir;
    }
    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
