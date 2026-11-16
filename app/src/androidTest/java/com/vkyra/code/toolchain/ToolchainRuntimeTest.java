package com.vkyra.code.toolchain;

import com.vkyra.code.lsp.ClangdClient;

import com.vkyra.code.terminal.ManagedProcessRunner;
import com.vkyra.code.toolchain.ClangFormatter;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CppCompiler;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real bundled toolchain, not a host compiler or protocol fake. */
@RunWith(AndroidJUnit4.class)
public class ToolchainRuntimeTest {
    @Test public void bundledCompilerFormatterAndClangdRunInSupplementaryUnicodeWorkspace() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClangInstaller.install(context,new ClangInstaller.InstallCallback(){
            public void onProgress(int current,int total,String file) { }
            public void onSuccess(int files) { }
            public void onError(String error) { throw new AssertionError(error); }
        });
        assertTrue(ClangInstaller.isInstalled(context));
        File directory=Files.createTempDirectory(context.getFilesDir().toPath(),"测试😀").toFile();
        File source=new File(directory,"main🧪.cpp"), executable=new File(directory,"test-program");
        Files.write(source.toPath(),"#include <stdio.h>\nint main(){puts(\"Vkyra😀\");return 0;}\n".getBytes(StandardCharsets.UTF_8));
        CppCompiler.CompileResult compiled=CppCompiler.compileToPath(context,source.getPath(),executable.getPath(),false);
        assertTrue(compiled.errorMessage,compiled.success);
        ManagedProcessRunner.Result ran=new ManagedProcessRunner(null).run(new ProcessBuilder(executable.getPath()).directory(directory),10000);
        assertEquals(ran.errorOutput,0,ran.exitCode);assertEquals("Vkyra😀\n",ran.output);
        CountDownLatch formatted=new CountDownLatch(1);AtomicReference<String> formatOutput=new AtomicReference<>(),formatError=new AtomicReference<>();
        ClangFormatter.formatAsync(context,"int main(){return 0;}\n",new ClangFormatter.FormatCallback(){
            public void onSuccess(String text){formatOutput.set(text);formatted.countDown();}
            public void onError(String error){formatError.set(error);formatted.countDown();}
        });
        assertTrue(formatted.await(30,TimeUnit.SECONDS));assertNull(formatError.get());assertTrue(formatOutput.get().contains("return 0;"));
        CountDownLatch initialized=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        ClangdClient client=new ClangdClient(context,directory);
        try {
            client.start(new ClangdClient.InitCallback(){public void onInitialized(){initialized.countDown();}public void onError(String value){error.set(value);initialized.countDown();}});
            assertTrue("bundled clangd initialize timed out",initialized.await(30,TimeUnit.SECONDS));assertNull(error.get());
        } finally {client.cleanup();}
    }
}
