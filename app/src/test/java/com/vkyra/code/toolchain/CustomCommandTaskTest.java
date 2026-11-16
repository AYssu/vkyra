package com.vkyra.code.toolchain;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real shell processes: pipe pressure, bounded capture and owned cancellation. */
public class CustomCommandTaskTest {
    private static CustomCommandTask.Request request(String command, File directory, long timeout) {
        return new CustomCommandTask.Request("/bin/sh", command, directory, timeout,
                "source snapshot", "output snapshot", "run snapshot");
    }

    @Test public void twoHundredKilobytesOfStderrCannotDeadlockOpenStdout() throws Exception {
        LinkedBlockingQueue<CustomCommandTask.Result> results = new LinkedBlockingQueue<>();
        try (CustomCommandTask task = new CustomCommandTask(request(
                "head -c 204800 /dev/zero | tr '\\000' E >&2; printf '中文 stdout'; exit 7",
                null, 5000), Runnable::run, results::add)) {
            CustomCommandTask.Result result = results.poll(10, TimeUnit.SECONDS);
            assertNotNull("stderr filled the pipe while stdout remained open", result);
            assertFalse(result.success);
            assertEquals(7, result.exitCode);
            assertEquals(204800, result.errorOutput.length());
            assertEquals("中文 stdout", result.output);
            assertEquals(result.errorOutput, result.errorMessage);
            assertEquals("source snapshot", result.request.sourceFile);
            task.whenClosed().get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void successUsesCapturedDirectoryAndPreservesBothOutputs() throws Exception {
        File directory = Files.createTempDirectory("custom-command-success-").toFile();
        LinkedBlockingQueue<CustomCommandTask.Result> results = new LinkedBlockingQueue<>();
        try (CustomCommandTask task = new CustomCommandTask(request(
                "pwd; printf complete; printf warning >&2", directory, 5000), Runnable::run, results::add)) {
            CustomCommandTask.Result result = results.poll(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.errorMessage, result.success);
            assertEquals(directory.getAbsolutePath() + "\ncomplete", result.output);
            assertEquals("warning", result.errorOutput);
            assertEquals("run snapshot", result.request.runCommand);
            assertFalse(result.outputTruncated);
            task.whenClosed().get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void excessiveOutputIsCappedButProcessStillFinishes() throws Exception {
        LinkedBlockingQueue<CustomCommandTask.Result> results = new LinkedBlockingQueue<>();
        try (CustomCommandTask task = new CustomCommandTask(request(
                "head -c 4400000 /dev/zero | tr '\\000' O; printf done >&2",
                null, 5000), Runnable::run, results::add)) {
            CustomCommandTask.Result result = results.poll(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.success);
            assertTrue(result.outputTruncated);
            assertEquals(4 * 1024 * 1024, result.output.length());
            assertEquals("done", result.errorOutput);
            task.whenClosed().get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void timeoutReportsFailureAfterStoppingOwnedDelayedWriter() throws Exception {
        File directory = Files.createTempDirectory("custom-command-timeout-").toFile();
        LinkedBlockingQueue<CustomCommandTask.Result> results = new LinkedBlockingQueue<>();
        try (CustomCommandTask task = new CustomCommandTask(request(
                "sleep 1; printf late > forbidden", directory, 100), Runnable::run, results::add)) {
            CustomCommandTask.Result result = results.poll(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.success);
            assertEquals("命令执行超时", result.errorMessage);
            task.whenClosed().get(5, TimeUnit.SECONDS);
            assertFalse(new File(directory, "forbidden").exists());
        }
    }

    @Test public void cancellationReapsSessionAndSuppressesCallbacksWithoutStoppingOtherCommands() throws Exception {
        File directory = Files.createTempDirectory("custom-command-cancel-").toFile();
        AtomicInteger callbacks = new AtomicInteger();
        LinkedBlockingQueue<CustomCommandTask.Result> independent = new LinkedBlockingQueue<>();
        try (CustomCommandTask task = new CustomCommandTask(request(
                "printf ready > ready; sleep 1; printf late > forbidden", directory, 5000),
                Runnable::run, result -> callbacks.incrementAndGet());
             CustomCommandTask other = new CustomCommandTask(request(
                     "sleep 0.2; printf independent", null, 5000), Runnable::run, independent::add)) {
            awaitFile(new File(directory, "ready"));
            task.cancel();
            task.whenClosed().get(5, TimeUnit.SECONDS);
            assertTrue(task.isCancelled());
            assertEquals(0, callbacks.get());
            assertFalse(new File(directory, "forbidden").exists());
            CustomCommandTask.Result result = independent.poll(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.errorMessage, result.success);
            assertEquals("independent", result.output);
            other.whenClosed().get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void cancellationDropsResultAlreadyQueuedForUiDelivery() throws Exception {
        LinkedBlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicInteger callbacks = new AtomicInteger();
        try (CustomCommandTask task = new CustomCommandTask(request("printf done", null, 5000),
                ui::add, result -> callbacks.incrementAndGet())) {
            Runnable delivery = ui.poll(10, TimeUnit.SECONDS);
            assertNotNull(delivery);
            task.whenClosed().get(5, TimeUnit.SECONDS);
            task.cancel();
            delivery.run();
            assertEquals(0, callbacks.get());
        }
    }

    private static void awaitFile(File file) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!file.exists() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue("Command never started", file.exists());
        assertEquals("ready", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
