package com.vkyra.code.terminal;

import org.junit.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ManagedProcessRunnerTest {
    @Test public void failurePreservesUnicodeDiagnosticsAndExitStatus() throws Exception {
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        ManagedProcessRunner.Result result = runner.run(new ProcessBuilder("/bin/sh", "-c", "printf '编译失败'; exit 7"), 5000);
        assertEquals(7, result.exitCode);
        assertEquals("编译失败", result.output);
        assertFalse(result.cancelled);
    }
    @Test public void successfulCommandReturnsOutput() throws Exception {
        ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(new ProcessBuilder("/bin/sh", "-c", "printf ok"), 5000);
        assertEquals(0, result.exitCode); assertEquals("ok", result.output);
    }
    @Test public void cancellationStopsOnlyTheOwnedCommand() throws Exception {
        ManagedProcessRunner target = new ManagedProcessRunner(null);
        ManagedProcessRunner other = new ManagedProcessRunner(null);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ManagedProcessRunner.Result> stopped = workers.submit(() -> target.run(new ProcessBuilder("/bin/sleep", "30"), 60000));
            Future<ManagedProcessRunner.Result> untouched = workers.submit(() -> other.run(new ProcessBuilder("/bin/sh", "-c", "sleep 1; printf untouched"), 60000));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!target.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(target.isRunning());
            target.cancel();
            assertTrue(stopped.get(5, TimeUnit.SECONDS).cancelled);
            assertEquals("untouched", untouched.get(5, TimeUnit.SECONDS).output);
        } finally { target.cancel(); other.cancel(); workers.shutdownNow(); }
    }
    @Test public void cancellingBeforeStartNeverLaunchesTheExecutable() throws Exception {
        ManagedProcessRunner runner = new ManagedProcessRunner(null); runner.cancel();
        assertTrue(runner.run(new ProcessBuilder("/does/not/exist"), 1000).cancelled);
    }
    @Test public void timeoutCancelsHungCommand() throws Exception {
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        assertTrue(runner.run(new ProcessBuilder("/bin/sleep", "30"), 100).cancelled);
    }
    @Test public void orphanHoldingStdoutCannotDefeatTimeoutOrAffectAnotherSession() throws Exception {
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ManagedProcessRunner.Result> orphan = workers.submit(() -> runner.run(new ProcessBuilder("/bin/sh", "-c", "sleep 30 & sleep .2; exit 0"), 500));
            Future<ManagedProcessRunner.Result> other = workers.submit(() -> new ManagedProcessRunner(null).run(new ProcessBuilder("/bin/sh", "-c", "sleep 1; printf untouched"), 5000));
            assertTrue(orphan.get(2, TimeUnit.SECONDS).cancelled);
            assertEquals("untouched", other.get(3, TimeUnit.SECONDS).output);
        } finally { runner.cancel(); workers.shutdownNow(); }
    }
    @Test public void cancellationStopsDelayedWriterBeforeCallerRestoresFiles() throws Exception {
        java.nio.file.Path output = java.nio.file.Files.createTempFile("vkyra-script-cancel", ".txt");
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ManagedProcessRunner.Result> future = worker.submit(() -> runner.run(new ProcessBuilder("/bin/sh", "-c",
                    "(sleep 1; printf bad > \"$1\") & sleep .2; exit 0", "test", output.toString()), 10000));
            Thread.sleep(350); runner.cancel();
            assertTrue(future.get(2, TimeUnit.SECONDS).cancelled);
            runner.awaitStopped();
            java.nio.file.Files.write(output, "old plugin restored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Thread.sleep(1100);
            assertEquals("old plugin restored", new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8));
        } finally { runner.cancel(); worker.shutdownNow(); java.nio.file.Files.deleteIfExists(output); }
    }
    @Test public void stdinAndBothOutputStreamsAreDrainedConcurrently() throws Exception {
        String code = new String(new char[200000]).replace('\0', 'x');
        ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(new ProcessBuilder("/bin/sh", "-c",
                "i=0; while [ \"$i\" -lt 2000 ]; do printf '%0100d' 0 >&2; i=$((i+1)); done; cat"), 5000, code, true);
        assertEquals(0, result.exitCode); assertEquals(code, result.output);
        assertEquals(200000, result.errorOutput.length());
    }
    @Test public void realClangFormatPreservesUnicodeAndReportsInvalidStyle() throws Exception {
        org.junit.Assume.assumeTrue(new java.io.File("/usr/bin/clang-format").canExecute());
        String code = "// 中文\nint main(){return 0;}";
        ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(new ProcessBuilder("/usr/bin/clang-format", "-style=Google"), 5000, code, true);
        assertEquals(0, result.exitCode); assertTrue(result.output.contains("// 中文")); assertTrue(result.output.contains("int main() { return 0; }"));
        ManagedProcessRunner.Result invalid = new ManagedProcessRunner(null).run(new ProcessBuilder("/usr/bin/clang-format", "-style={InvalidOption: true}"), 5000, code, true);
        assertNotEquals(0, invalid.exitCode); assertTrue(invalid.errorOutput.contains("InvalidOption"));
    }
}
