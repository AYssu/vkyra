package com.vkyra.code.installer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ToolchainTransactionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String TOOL = "clang-test", MARKER = ".clang_installed";

    @Test public void failedExtractionPreservesWorkingCompilerMarkerAndOtherUserFiles() throws Exception {
        File root = previous();
        try {
            install(new ToolchainTransaction(), root, new InstallCancellation(), (stage, token) -> {
                replacement(stage, token);
                throw new IOException("injected disk full");
            });
            fail("Must fail");
        } catch (IOException expected) { }
        assertPrevious(root);
        assertFalse(work(root).resolve("staged").toFile().exists());
    }

    @Test public void failureAtEveryActivationBoundaryRestoresOldInstallation() throws Exception {
        for (String boundary : new String[]{"before-backup:" + MARKER, "after-backup:" + MARKER,
                "after-activate:" + MARKER, "before-backup:" + TOOL, "after-backup:" + TOOL,
                "after-activate:" + TOOL, "before-commit"}) {
            File root = previous();
            ToolchainTransaction transaction = new ToolchainTransaction(point -> {
                if (point.equals(boundary)) throw new IOException("injected " + point);
            });
            try { install(transaction, root, new InstallCancellation(), this::replacement); fail(boundary); }
            catch (IOException expected) { }
            assertPrevious(root);
            assertFalse(work(root).resolve("state.properties").toFile().exists());
        }
    }

    @Test public void cancellationAfterActivationRollsBackBeforeReturning() throws Exception {
        File root = previous();
        InstallCancellation cancellation = new InstallCancellation();
        ToolchainTransaction transaction = new ToolchainTransaction(point -> {
            if (point.equals("after-activate:" + TOOL)) cancellation.cancel();
        });
        try { install(transaction, root, cancellation, this::replacement); fail("Cancelled"); }
        catch (InterruptedIOException expected) { }
        assertPrevious(root);
    }

    @Test public void cancellationAtCommitDecisionStillRestoresOldInstallation() throws Exception {
        File root = previous();
        InstallCancellation cancellation = new InstallCancellation();
        try {
            install(new ToolchainTransaction(point -> {
                if (point.equals("before-commit")) cancellation.cancel();
            }), root, cancellation, this::replacement);
            fail("Cancelled");
        } catch (InterruptedIOException expected) { }
        assertPrevious(root);
    }

    @Test public void successfulReplacementKeepsUserFilesAndLeavesNoBackup() throws Exception {
        File root = previous();
        assertEquals(1, install(new ToolchainTransaction(), root, new InstallCancellation(), this::replacement));
        assertEquals("new compiler", read(root, TOOL + "/bin/clang"));
        assertTrue(new File(root, MARKER).isFile());
        assertEquals("user draft", read(root, "project.cpp"));
        assertFalse(work(root).resolve("backup").toFile().exists());
    }

    @Test public void invalidReplacementIsRejectedBeforeMovingAnyLiveFile() throws Exception {
        File root = previous();
        try {
            install(new ToolchainTransaction(), root, new InstallCancellation(), (stage, token) -> {
                Files.createDirectories(new File(stage, TOOL).toPath());
                return 1;
            });
            fail("Missing compiler");
        } catch (IOException expected) { }
        assertPrevious(root);
    }

    @Test public void crashDuringCommitIsRecoveredBeforeInstalledMarkerIsAccepted() throws Exception {
        File root = previous();
        try {
            install(new ToolchainTransaction(point -> {
                if (point.equals("after-activate:" + TOOL)) throw new SimulatedDeath();
            }), root, new InstallCancellation(), this::replacement);
            fail("Simulated process death");
        } catch (SimulatedDeath expected) { }
        assertTrue(work(root).resolve("state.properties").toFile().isFile());
        AtomicBoolean extracted = new AtomicBoolean();
        int count = new ToolchainTransaction().install(root, TOOL, MARKER, false, new InstallCancellation(),
                (stage, token) -> { extracted.set(true); return replacement(stage, token); }, this::validate);
        assertEquals(0, count);
        assertFalse(extracted.get());
        assertPrevious(root);
    }

    @Test public void firstInstallFailureDoesNotLeaveMarkerOrPartialToolchain() throws Exception {
        File root = temporary.newFolder();
        try {
            install(new ToolchainTransaction(point -> {
                if (point.equals("after-activate:" + TOOL)) throw new IOException("injected activation error");
            }), root, new InstallCancellation(), this::replacement);
            fail("Must fail");
        } catch (IOException expected) { }
        assertFalse(new File(root, TOOL).exists());
        assertFalse(new File(root, MARKER).exists());
    }

    @Test public void concurrentInstallOfSameToolchainFailsWithoutTouchingActiveOwner() throws Exception {
        File root = previous();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(() -> install(new ToolchainTransaction(), root, new InstallCancellation(), (stage, token) -> {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException interrupted) { throw new InterruptedIOException(); }
                return replacement(stage, token);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try { install(new ToolchainTransaction(), root, new InstallCancellation(), this::replacement); fail("Concurrent owner"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("已有安装任务")); }
            assertPrevious(root);
            release.countDown();
            assertEquals(1, active.get(2, TimeUnit.SECONDS).intValue());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void deletingStagingNeverFollowsLinkToWorkingInstallation() throws Exception {
        File root = previous();
        Path staging = new File(root, "sample-stage").toPath();
        Files.createDirectories(staging);
        Files.createSymbolicLink(staging.resolve("escape"), new File(root, TOOL).toPath());
        ToolchainTransaction.deleteTree(staging);
        assertPrevious(root);
    }

    private int install(ToolchainTransaction transaction, File root, InstallCancellation token,
                        ToolchainTransaction.Preparation preparation) throws IOException {
        return transaction.install(root, TOOL, MARKER, true, token, preparation, this::validate);
    }
    private File previous() throws IOException {
        File root = temporary.newFolder();
        Path compiler = new File(root, TOOL + "/bin/clang").toPath();
        Files.createDirectories(compiler.getParent());
        Files.write(compiler, "working compiler".getBytes());
        Files.write(new File(root, MARKER).toPath(), "old marker".getBytes());
        Files.write(new File(root, "project.cpp").toPath(), "user draft".getBytes());
        return root;
    }
    private int replacement(File staging, InstallCancellation cancellation) throws IOException {
        Path compiler = new File(staging, TOOL + "/bin/clang").toPath();
        Files.createDirectories(compiler.getParent());
        Files.write(compiler, "new compiler".getBytes());
        return 1;
    }
    private void validate(File root) throws IOException {
        if (!new File(root, TOOL + "/bin/clang").isFile()) throw new IOException("Missing compiler");
    }
    private void assertPrevious(File root) throws IOException {
        assertEquals("working compiler", read(root, TOOL + "/bin/clang"));
        assertEquals("old marker", read(root, MARKER));
        assertEquals("user draft", read(root, "project.cpp"));
    }
    private static String read(File root, String name) throws IOException { return new String(Files.readAllBytes(new File(root, name).toPath())); }
    private static Path work(File root) { return new File(root, ".toolchain-transactions/" + TOOL).toPath(); }
    private static final class SimulatedDeath extends Error { private static final long serialVersionUID = 1L; }
}
