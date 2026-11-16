package com.vkyra.code.toolchain;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import androidx.lifecycle.*;
import androidx.test.core.app.ApplicationProvider;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, manifest = Config.NONE)
public class ClangFormatterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private TestContext context;
    private File binary, config;
    @Before public void setup() throws Exception {
        context = new TestContext(ApplicationProvider.getApplicationContext(), temporary.newFolder());
        binary = new File(ClangInstaller.getClangDir(context), "bin/clang-format");
        Files.createDirectories(binary.getParentFile().toPath());
        config = new File(context.getFilesDir(), ".clang-format");
        Files.write(config.toPath(), "---\nBasedOnStyle: LLVM\n".getBytes(StandardCharsets.UTF_8));
    }
    @Test public void resultPreservesExactNewlinesAndExcludesStderr() throws Exception {
        executable("printf warning >&2; cat\n");
        ClangFormatter.FormatResult result = ClangFormatter.format(context, "int main() {}\r\n");
        assertTrue(result.errorMessage, result.success);
        assertEquals("int main() {}\r\n", result.formattedCode);
    }
    @Test public void invalidUserConfigurationIsPreservedAndReportsToolDiagnostics() throws Exception {
        Files.write(config.toPath(), "not valid YAML!!!".getBytes(StandardCharsets.UTF_8));
        executable("printf 'invalid style' >&2; exit 2\n");
        ClangFormatter.FormatResult result = ClangFormatter.format(context, "int main(){}");
        assertFalse(result.success); assertEquals("invalid style", result.errorMessage);
        assertEquals("not valid YAML!!!", new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void destroyCancelsFormattingAndDropsPageCallback() throws Exception {
        executable("sleep 30\n");
        context.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicBoolean delivered = new AtomicBoolean();
        ClangFormatter.FormatTask task = ClangFormatter.formatAsync(context, "int main(){}", new ClangFormatter.FormatCallback() {
            public void onSuccess(String code) { delivered.set(true); }
            public void onError(String message) { delivered.set(true); }
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        context.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(task.isCancelled()); assertFalse(delivered.get());
    }
    private void executable(String body) throws Exception {
        Files.write(binary.toPath(), ("#!/bin/sh\n" + body).getBytes(StandardCharsets.UTF_8));
        assertTrue(binary.setExecutable(true));
    }
    private static final class TestContext extends ContextWrapper implements LifecycleOwner {
        private final File files;
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        TestContext(Context base, File files) { super(base); this.files = files; }
        @Override public File getFilesDir() { return files; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Lifecycle getLifecycle() { return lifecycle; }
    }
}
