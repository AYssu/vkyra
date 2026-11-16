package com.vkyra.code.toolchain;

import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.terminal.ShellConfiguration;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, manifest = Config.NONE)
public class CppCompilerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private TestContext context;
    private File compiler;
    @Before public void setup() throws Exception {
        context = new TestContext(ApplicationProvider.getApplicationContext(), temporary.newFolder());
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        File bin = new File(ClangInstaller.getClangDir(context), "bin");
        Files.createDirectories(bin.toPath());
        compiler = new File(bin, "clang++");
    }
    @Test public void compilerFailurePreservesPriorBinaryAndReportsDiagnostics() throws Exception {
        executable("printf 'diagnostic: bad source'; exit 7\n");
        File output = temporary.newFile(); Files.write(output.toPath(), "old binary".getBytes(StandardCharsets.UTF_8));
        CppCompiler.CompileResult result = CppCompiler.compileToPath(context, "/project/source.cpp", output.getPath(), false);
        assertFalse(result.success); assertTrue(result.errorMessage.contains("diagnostic: bad source"));
        assertEquals("old binary", new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void successfulCompilePublishesArtifactAndDefaultBuildsDoNotShareOutput() throws Exception {
        executable("last=''; for argument do if [ \"$last\" = '-o' ]; then printf binary > \"$argument\"; fi; last=$argument; done\n");
        CppCompiler.CompileResult first = CppCompiler.compile(context, "/project/source.cpp");
        CppCompiler.CompileResult second = CppCompiler.compile(context, "/project/source.cpp");
        assertTrue(first.errorMessage, first.success); assertTrue(second.errorMessage, second.success);
        assertNotEquals(first.outputPath, second.outputPath);
        assertEquals("binary", new String(Files.readAllBytes(new File(first.outputPath).toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(first.outputPath).canExecute());
    }
    @Test public void successfulExitWithoutArtifactDoesNotReplacePriorBinary() throws Exception {
        executable("exit 0\n");
        File output = temporary.newFile(); Files.write(output.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        CppCompiler.CompileResult result = CppCompiler.compileToPath(context, "/project/source.cpp", output.getPath(), false);
        assertFalse(result.success);
        assertEquals("old", new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void consecutiveDefaultBuildsIgnoreLegacyOutputAndPublishCurrentSource() throws Exception {
        // The fake compiler creates a real runnable script from the source payload.
        executable("source=$1; out=''; last=''; for argument do if [ \"$last\" = '-o' ]; then out=$argument; fi; last=$argument; done\n"
                + "{ printf '#!/bin/sh\\n'; printf 'printf '; /bin/cat \"$source\"; printf '\\n'; } > \"$out\"\n");
        File tmp = new File(context.getFilesDir(), "tmp"); assertTrue(tmp.mkdir());
        File old = new File(tmp, "a.out"); Files.write(old.toPath(), "#!/bin/sh\nprintf old\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(old.setExecutable(true));
        File source = temporary.newFile("new-source.cpp"); Files.write(source.toPath(), "fresh".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(tmp, "temp.c").toPath(), "fresh".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 2; i++) {
            CppCompiler.CompileResult result = CppCompiler.compile(context, source.getPath());
            assertTrue(result.errorMessage, result.success);
            assertNotEquals(old.getPath(), result.outputPath);
            com.vkyra.code.terminal.ManagedProcessRunner.Result run = new com.vkyra.code.terminal.ManagedProcessRunner(null)
                    .run(new ProcessBuilder(result.outputPath), 5000);
            assertEquals(run.output, 0, run.exitCode);
            assertEquals("fresh", run.output);
        }
        assertEquals("#!/bin/sh\nprintf old\n", new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void defaultFailureRemovesItsPrivateBuildDirectory() throws Exception {
        executable("exit 7\n");
        assertFalse(CppCompiler.compile(context, "/project/source.cpp").success);
        File tmp = new File(context.getFilesDir(), "tmp");
        File[] builds = tmp.listFiles(file -> file.getName().startsWith("build-vkyra-"));
        assertNotNull(builds); assertEquals(0, builds.length);
    }
    @Test public void cArgumentsRemoveCppOnlyOptionsAndKeepQuotedIncludes() {
        assertEquals(Arrays.asList("-Ipath with spaces", "-O2"), CppCompiler.compilerFlags("-std=c++20 -stdlib=libc++ -I'path with spaces' -O2", true));
    }
    @Test public void destroyedPageCancelsAndDetachesItsCallback() throws Exception {
        executable("/bin/sleep 30\n");
        context.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicBoolean callback = new AtomicBoolean();
        CppCompiler.CompileTask task = CppCompiler.compileAsync(context, "/project/source.cpp", new CppCompiler.CompileCallback() {
            public void onSuccess(String path) { callback.set(true); }
            public void onError(String error) { callback.set(true); }
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        context.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(task.isCancelled()); assertFalse(callback.get());
    }
    @Test public void terminalDefaultsPreserveExistingUserShellConfiguration() throws Exception {
        File bashrc = new File(context.getFilesDir(), ".bashrc");
        Files.write(bashrc.toPath(), "# user custom environment".getBytes(StandardCharsets.UTF_8));
        ShellConfiguration.initialize(context);
        assertEquals("# user custom environment", new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(context.getFilesDir(), ".profile").isFile());
    }
    private void executable(String body) throws Exception {
        Files.write(compiler.toPath(), ("#!/bin/sh\n" + body).getBytes(StandardCharsets.UTF_8));
        assertTrue(compiler.setExecutable(true));
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
