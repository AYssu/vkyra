package com.vkyra.code.plugin;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PluginInstallScriptTest {
    @Test public void failedCommandAbortsInstallInsteadOfReportingSuccess() throws Exception {
        File directory = fixture("{\"install_before\":[{\"node\":\"command\",\"sh\":\"exit 7\"}]}");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean incorrectlyCompleted = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        try (ActivityScenario<PluginActivity> scenario = ActivityScenario.launch(PluginActivity.class)) {
            scenario.onActivity(activity -> {
                PluginInstallScript script = new PluginInstallScript(activity, directory);
                script.setCallback(new PluginInstallScript.InstallCallback() {
                    public void onProgress(String message) { }
                    public void onComplete(boolean success, String message) { failed.set(!success); finished.countDown(); }
                });
                script.executeInstallBefore(() -> { incorrectlyCompleted.set(true); finished.countDown(); });
            });
            assertTrue("command completion", finished.await(15, TimeUnit.SECONDS));
            assertTrue(failed.get());
            assertFalse(incorrectlyCompleted.get());
        } finally { PluginInstallTransaction.deleteTree(directory); }
    }

    @Test public void explicitCommandErrorBranchCanRecover() throws Exception {
        File directory = fixture("{\"install_before\":[{\"node\":\"command_result\",\"sh\":\"exit 9\","
                + "\"error\":[{\"node\":\"create_value\",\"name\":\"recovered\",\"value\":\"yes\"}]}]}");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean recovered = new AtomicBoolean();
        try (ActivityScenario<PluginActivity> scenario = ActivityScenario.launch(PluginActivity.class)) {
            scenario.onActivity(activity -> {
                PluginInstallScript script = new PluginInstallScript(activity, directory);
                script.executeInstallBefore(() -> { recovered.set("yes".equals(script.getVariable("recovered"))); finished.countDown(); });
            });
            assertTrue("error branch completion", finished.await(15, TimeUnit.SECONDS));
            assertTrue(recovered.get());
        } finally { PluginInstallTransaction.deleteTree(directory); }
    }

    @Test public void destroyedHookCannotWriteIntoRestoredPlugin() throws Exception {
        File directory = fixture("{}");
        File started = new File(directory, "started"), target = new File(directory, "payload");
        java.util.concurrent.atomic.AtomicReference<PluginInstallScript> reference = new java.util.concurrent.atomic.AtomicReference<>();
        ActivityScenario<PluginActivity> scenario = ActivityScenario.launch(PluginActivity.class);
        try {
            scenario.onActivity(activity -> {
                PluginInstallScript script = new PluginInstallScript(activity, directory);
                reference.set(script);
                try {
                    org.json.JSONObject command = new org.json.JSONObject().put("node", "command")
                            .put("sh", "printf ready > started; (sleep 1; printf bad > payload) & sleep .2; exit 0");
                    script.executeNodes(new org.json.JSONArray().put(command), () -> fail("Destroyed hook completed"));
                } catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!started.exists() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("hook process started", started.exists());
            scenario.close();
            reference.get().cancelAndAwait();
            Files.write(target.toPath(), "old plugin restored".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(1200);
            assertEquals("old plugin restored", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } finally { scenario.close(); reference.get().cancelAndAwait(); PluginInstallTransaction.deleteTree(directory); }
    }

    @Test public void oneHundredShortEventsKeepObserverCountAtBaseline() throws Exception {
        File directory = fixture("{}");
        File plugin = new File(directory, "plugin/short-hook");
        Files.createDirectories(plugin.toPath());
        Files.write(new File(plugin, ".enabled").toPath(), new byte[0]);
        Files.write(new File(plugin, ".hook").toPath(), ("{\"events\":{\"on_compile_before\":["
                + "{\"node\":\"create_value\",\"name\":\"value\",\"value\":\"ok\"}]}}")
                .getBytes(StandardCharsets.UTF_8));
        CountDownLatch completed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger baseline = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peakAfterCompletion = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<PluginActivity> scenario = ActivityScenario.launch(PluginActivity.class)) {
            scenario.onActivity(activity -> {
                android.content.Context filesContext = new android.content.ContextWrapper(activity.getApplicationContext()) {
                    @Override public File getFilesDir() { return directory; }
                };
                PluginHookManager manager = new PluginHookManager(filesContext);
                androidx.lifecycle.LifecycleRegistry lifecycle = (androidx.lifecycle.LifecycleRegistry) activity.getLifecycle();
                baseline.set(lifecycle.getObserverCount());
                java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
                Runnable[] next = new Runnable[1];
                next[0] = () -> manager.executeEvent(activity, PluginHookManager.EVENT_COMPILE_BEFORE,
                        java.util.Collections.emptyMap(), () -> {
                    peakAfterCompletion.accumulateAndGet(lifecycle.getObserverCount(), Math::max);
                    if (executions.incrementAndGet() == 100) completed.countDown(); else next[0].run();
                });
                next[0].run();
            });
            assertTrue("100 hook completions", completed.await(15, TimeUnit.SECONDS));
            assertEquals("completed hooks must release their observers", baseline.get(), peakAfterCompletion.get());
        } finally { PluginInstallTransaction.deleteTree(directory); }
    }

    @Test public void emptyHooksAndCompleteInstallReleaseButIntermediatePhasesStayObserved() throws Exception {
        File directory = fixture("{}");
        try (ActivityScenario<PluginActivity> scenario = ActivityScenario.launch(PluginActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.lifecycle.LifecycleRegistry lifecycle = (androidx.lifecycle.LifecycleRegistry) activity.getLifecycle();
                int baseline = lifecycle.getObserverCount();
                for (int i = 0; i < 100; i++) {
                    new PluginInstallScript(activity, directory).executeNodes(new org.json.JSONArray(), null);
                }
                assertEquals("empty hooks release immediately", baseline, lifecycle.getObserverCount());
                PluginInstallScript installation = new PluginInstallScript(activity, directory);
                assertEquals(baseline + 1, lifecycle.getObserverCount());
                installation.executeInstallBefore(() -> { });
                assertEquals("before phase retains install ownership", baseline + 1, lifecycle.getObserverCount());
                installation.executeInstallStart(() -> { });
                assertEquals("start phase retains install ownership", baseline + 1, lifecycle.getObserverCount());
                installation.executeInstallAfter(() -> { });
                assertEquals("complete install releases ownership", baseline, lifecycle.getObserverCount());
                PluginInstallScript noScript = new PluginInstallScript(activity, directory);
                noScript.releaseLifecycle();
                assertEquals("no-script install releases explicitly", baseline, lifecycle.getObserverCount());
            });
        } finally { PluginInstallTransaction.deleteTree(directory); }
    }

    private static File fixture(String script) throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File directory = Files.createTempDirectory(cache.toPath(), "plugin-script-test").toFile();
        Files.write(new File(directory, ".install").toPath(), script.getBytes(StandardCharsets.UTF_8));
        return directory;
    }
}
