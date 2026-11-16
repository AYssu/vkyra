package com.vkyra.code.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class PluginZipExtractorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void extractsBinaryAndUnicodeFilesWithoutChangingContent() throws Exception {
        File zip = archive(".config", "{\"plugin_code\":\"demo\"}", "include/中文.h", "你好\r\n");
        File target = temp.newFolder();
        PluginZipExtractor.extract(zip, target, null);
        assertEquals("你好\r\n", new String(Files.readAllBytes(new File(target, "include/中文.h").toPath()), StandardCharsets.UTF_8));
        assertTrue(PluginZipExtractor.readConfig(zip).contains("demo"));
    }

    @Test public void traversalIsRejectedWithoutWritingOutside() throws Exception {
        File zip = archive("../escape", "malicious");
        File parent = temp.newFolder();
        File target = new File(parent, "plugin");
        try { PluginZipExtractor.extract(zip, target, null); fail(); }
        catch (SecurityException expected) { }
        assertFalse(new File(parent, "escape").exists());
    }

    @Test public void oversizedConfigRejectedBeforeAllocation() throws Exception {
        String huge = new String(new char[(int) PluginZipExtractor.MAX_CONFIG_BYTES + 1]).replace('\0', 'x');
        File zip = archive(".config", huge);
        try { PluginZipExtractor.readConfig(zip); fail(); }
        catch (java.io.IOException expected) { }
    }

    @Test public void interruptedExtractionCancelsAndPreservesOldPlugin() throws Exception {
        File root = temp.newFolder();
        File old = new File(root, "demo/old");
        Files.createDirectories(old.getParentFile().toPath());
        Files.write(old.toPath(), new byte[]{42});
        File zip = archive(".config", "{}");
        try (PluginInstallTransaction tx = PluginInstallTransaction.begin(root, "demo")) {
            Thread.currentThread().interrupt();
            try { PluginZipExtractor.extract(zip, tx.getStagingDirectory(), null); fail(); }
            catch (InterruptedException expected) { }
            finally { Thread.interrupted(); }
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(old.toPath()));
    }

    private File archive(String... entries) throws Exception {
        File file = temp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
