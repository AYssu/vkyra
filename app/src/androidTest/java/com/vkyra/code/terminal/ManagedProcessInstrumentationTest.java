package com.vkyra.code.terminal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ManagedProcessInstrumentationTest {
    @Test public void orphanWriterTimeoutIsBoundedAndOtherSessionSurvives() throws Exception {
        ExecutorService worker = Executors.newFixedThreadPool(2);
        ManagedProcessRunner first = new ManagedProcessRunner(null), other = new ManagedProcessRunner(null);
        try {
            Future<ManagedProcessRunner.Result> orphan = worker.submit(() -> first.run(new ProcessBuilder("/system/bin/sh", "-c", "sleep 30 & sleep .2; exit 0"), 500));
            Future<ManagedProcessRunner.Result> second = worker.submit(() -> other.run(new ProcessBuilder("/system/bin/sh", "-c", "sleep 1; printf untouched"), 5000));
            assertTrue(orphan.get(5, TimeUnit.SECONDS).cancelled);
            assertEquals("untouched", second.get(5, TimeUnit.SECONDS).output);
        } finally { first.cancel(); other.cancel(); worker.shutdownNow(); }
    }
    @Test public void cancelBarrierPreventsDelayedWritesAfterRestoration() throws Exception {
        File target = File.createTempFile("owned-script-", ".txt", InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        try {
            Future<ManagedProcessRunner.Result> result = worker.submit(() -> runner.run(new ProcessBuilder("/system/bin/sh", "-c",
                    "(sleep 1; printf bad > \"$1\") & sleep .2; exit 0", "test", target.getPath()), 10000));
            Thread.sleep(350); runner.cancel(); assertTrue(result.get(5, TimeUnit.SECONDS).cancelled); runner.awaitStopped();
            Files.write(target.toPath(), "restored old plugin".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(1200);
            assertEquals("restored old plugin", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } finally { runner.cancel(); worker.shutdownNow(); Files.deleteIfExists(target.toPath()); }
    }
    @Test public void supplementaryUnicodeSurvivesCwdArgumentsEnvironmentAndStdin() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "项目😀").toFile();
        File source = new File(directory, "源码🧪.cpp");
        Files.write(source.toPath(), "// 内容😀".getBytes(StandardCharsets.UTF_8));
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", "cat \"$1\"; printf '%s' \"$VKYRA_UTF8\"; cat", "test", source.getName());
        builder.directory(directory); builder.environment().put("VKYRA_UTF8", "环境🌍");
        ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(builder, 5000, "输入🚀", true);
        assertEquals(result.errorOutput, 0, result.exitCode);
        assertEquals("// 内容😀环境🌍输入🚀", result.output);
    }

    @Test public void largeStdinStdoutAndDiagnosticsCannotDeadlock() throws Exception {
        String input = new String(new char[200000]).replace('\0', 'x');
        ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(new ProcessBuilder("/system/bin/sh", "-c",
                "printf '%0200000d' 0 >&2; cat"), 10000, input, true);
        assertEquals(0, result.exitCode); assertEquals(input, result.output); assertEquals(200000, result.errorOutput.length());
    }
}
