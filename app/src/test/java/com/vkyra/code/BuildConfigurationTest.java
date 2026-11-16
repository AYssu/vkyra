package com.vkyra.code;

import com.vkyra.code.toolchain.BuildConfiguration;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BuildConfigurationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void preservesLegacyCommentsSpacesUnicodeAndRelativeEntries() throws Exception {
        File root = temporary.newFolder();
        Files.write(new File(root, ".compile_config").toPath(), "# note\n\n main.cpp \r\nsub dir/中文.c\n../shared.cpp\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(BuildConfiguration.isMultiFileMode(root));
        assertEquals(Arrays.asList("main.cpp", "sub dir/中文.c", "../shared.cpp"), BuildConfiguration.readMultiFileList(root));
    }
    @Test public void missingConfigIsSingleFileAndReturnedListImmutable() throws Exception {
        File root = temporary.newFolder();
        assertFalse(BuildConfiguration.isMultiFileMode(root));
        assertTrue(BuildConfiguration.readMultiFileList(root).isEmpty());
        Files.write(new File(root, ".compile_config").toPath(), "main.cpp".getBytes(StandardCharsets.UTF_8));
        try { BuildConfiguration.readMultiFileList(root).add("other.cpp"); fail(); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void malformedOrOversizedConfigurationDoesNotSilentlyBuildAnotherFile() throws Exception {
        File root = temporary.newFolder();
        File config = new File(root, ".compile_config");
        Files.write(config.toPath(), new byte[1024 * 1024 + 1]);
        try { BuildConfiguration.readMultiFileList(root); fail(); } catch (java.io.IOException expected) { }
        Files.delete(config.toPath()); Files.createDirectory(config.toPath());
        assertFalse(BuildConfiguration.isMultiFileMode(root));
    }
}
