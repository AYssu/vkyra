package com.vkyra.code.installer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ToolchainArchivesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void standardTarLibraryExtractsExecutableAndRelativeSymlinkOnlyInStage() throws Exception {
        File stage = temporary.newFolder();
        TrackedInput input = new TrackedInput(tar("tool/bin/compiler", false));
        assertEquals(2, ToolchainArchives.tarXz(input, stage, "tool", new InstallCancellation(), null));
        assertTrue(input.closed);
        assertEquals("compiler", new String(Files.readAllBytes(new File(stage, "tool/bin/compiler").toPath())));
        assertTrue(Files.isSymbolicLink(new File(stage, "tool/bin/cc").toPath()));
        ToolchainArchives.requireFiles(stage, "tool", "bin/compiler", "bin/cc");
    }

    @Test public void failedArchiveClosesInputAndKeepsPreviousToolchain() throws Exception {
        File root = temporary.newFolder();
        Path compiler = new File(root, "tool/bin/compiler").toPath();
        Files.createDirectories(compiler.getParent());
        Files.write(compiler, "old compiler".getBytes());
        Path marker = new File(root, ".installed").toPath();
        Files.write(marker, "old marker".getBytes());
        TrackedInput corrupt = new TrackedInput(new byte[]{0, 1, 2, 3});
        try {
            new ToolchainTransaction().install(root, "tool", ".installed", true, new InstallCancellation(),
                    (staging, token) -> ToolchainArchives.tarXz(corrupt, staging, "tool", token, null),
                    staging -> ToolchainArchives.requireFiles(staging, "tool", "bin/compiler"));
            fail("Corrupt archive");
        } catch (IOException expected) { }
        assertTrue(corrupt.closed);
        assertEquals("old compiler", new String(Files.readAllBytes(compiler)));
        assertEquals("old marker", new String(Files.readAllBytes(marker)));
    }

    @Test public void tarRejectsSiblingPrefixAndOutsideSymlinkWithoutTouchingOutsideFiles() throws Exception {
        for (boolean symlink : new boolean[]{false, true}) {
            File stage = temporary.newFolder();
            TrackedInput input = new TrackedInput(tar(symlink ? "tool/bin/compiler" : "tool-evil/escape", symlink));
            try { ToolchainArchives.tarXz(input, stage, "tool", new InstallCancellation(), null); fail("Out of root"); }
            catch (IOException expected) { }
            assertTrue(input.closed);
            assertFalse(new File(stage, "tool-evil/escape").exists());
        }
    }

    @Test public void zipUsesSameOwnershipCheckAndClosesInputOnCancelledExtraction() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("tool/bin/compiler"));
            zip.write("compiler".getBytes());
            zip.closeEntry();
        }
        File stage = temporary.newFolder();
        InstallCancellation cancellation = new InstallCancellation();
        TrackedInput input = new TrackedInput(bytes.toByteArray());
        try {
            ToolchainArchives.zip(input, stage, "tool", cancellation, (count, total, name) -> cancellation.cancel());
            // A tiny stream can already be at EOF when the progress callback cancels it. The
            // transaction's mandatory pre-validation cancellation check is still authoritative.
            cancellation.check();
            fail("Cancelled");
        } catch (IOException expected) { }
        assertTrue(input.closed);
        bytes.reset();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write(1);
            zip.closeEntry();
        }
        input = new TrackedInput(bytes.toByteArray());
        try { ToolchainArchives.zip(input, temporary.newFolder(), "tool", new InstallCancellation(), null); fail("Traversal"); }
        catch (IOException expected) { }
        assertTrue(input.closed);
    }

    @Test public void callbackFailureAlsoClosesArchiveBeforeTransactionReturns() throws Exception {
        TrackedInput input = new TrackedInput(tar("tool/bin/compiler", false));
        try {
            ToolchainArchives.tarXz(input, temporary.newFolder(), "tool", new InstallCancellation(),
                    (current, total, name) -> { throw new IllegalStateException("page callback failed"); });
            fail("Callback failure");
        } catch (IllegalStateException expected) { }
        assertTrue(input.closed);
    }

    private static byte[] tar(String file, boolean outsideLink) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZOutputStream xz = new XZOutputStream(bytes, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            byte[] content = "compiler".getBytes();
            TarArchiveEntry entry = new TarArchiveEntry(file);
            entry.setSize(content.length);
            entry.setMode(0755);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            TarArchiveEntry link = new TarArchiveEntry("tool/bin/cc", TarConstants.LF_SYMLINK);
            link.setLinkName(outsideLink ? "../../outside" : "compiler");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        volatile boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
