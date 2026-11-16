package com.vkyra.code.files;

import com.vkyra.code.Log;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.topjohnwu.superuser.Shell;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootFileUtilsInstrumentedTest {
    @Test public void internalAndAppExternalFilesSaveWithoutLosingTextOrBom() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (File directory : new File[]{context.getCacheDir(), context.getExternalFilesDir(null)}) {
            assertNotNull(directory);
            File file = File.createTempFile("vkyra-storage-regression-", ".cpp", directory);
            try {
                byte[] original = "\ufeff// 中文\r\nint main() {}".getBytes(StandardCharsets.UTF_8);
                Files.write(file.toPath(), original);
                String loaded = RootFileUtils.readFile(file.getPath());
                assertEquals("// 中文\r\nint main() {}", loaded);
                RootFileUtils.writeFile(file.getPath(), loaded);
                assertArrayEquals(original, Files.readAllBytes(file.toPath()));
                RootFileUtils.writeFile(file.getPath(), "// 修改\nint main() { return 0; }");
                assertEquals("\ufeff// 修改\r\nint main() { return 0; }",
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } finally {
                assertTrue(file.delete());
            }
        }
    }

    @Test public void rootStoragePreservesOwnerAndModeOrReportsPermissionDenied() throws Exception {
        String directory = "/data/local/tmp/vkyra_test_only_storage_" + UUID.randomUUID();
        String file = directory + "/source '$()` name.cpp";
        boolean available = RootFileUtils.isRootAvailable();
        android.util.Log.i("VkyraStorageTest", "Root branch available: " + available);
        if (!available) {
            try {
                RootFileUtils.writeFile(file, "test-only text");
                fail("An unprivileged app must not claim a root save succeeded");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("权限"));
            }
            return;
        }
        String quotedDirectory = RootFileCommands.quote(directory);
        String quotedFile = RootFileCommands.quote(file);
        assertTrue(Shell.cmd("mkdir -m 700 " + quotedDirectory + " && printf '%s' 'original' > "
                + quotedFile + " && chmod 600 " + quotedFile).exec().isSuccess());
        try {
            assertFalse(new File(file).canRead());
            assertEquals("original", RootFileUtils.readFile(file));
            RootFileUtils.writeFile(file, "edited without final newline");
            assertEquals("edited without final newline", RootFileUtils.readFile(file));
            Shell.Result stat = Shell.cmd("stat -c %u:%g:%a " + quotedFile).exec();
            assertTrue(stat.isSuccess());
            assertEquals("0:0:600", stat.getOut().get(0));
        } finally {
            assertTrue(Shell.cmd("rm -rf -- " + quotedDirectory).exec().isSuccess());
        }
    }
}
