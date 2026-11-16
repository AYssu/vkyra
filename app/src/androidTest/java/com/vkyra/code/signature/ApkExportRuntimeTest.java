package com.vkyra.code.signature;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.apksig.ApkVerifier;
import com.vkyra.code.terminal.ManagedProcessRunner;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CppCompiler;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Produces installable acceptance artifacts using the Android-bundled compiler, never an ELF stub. */
@RunWith(AndroidJUnit4.class)
public class ApkExportRuntimeTest {
    public static final String EXPORTED_PACKAGE = "com.vkyra.export.acceptance";

    @Test public void realNativeV1AndV2ExportWithOneSigningIdentityAndVerifiedPayloads() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotSame(Looper.getMainLooper(),Looper.myLooper());
        ClangInstaller.install(context,null);
        assertTrue("Bundled Clang installation is required",ClangInstaller.isInstalled(context));
        File artifacts=context.getExternalFilesDir("acceptance");
        assertNotNull("Acceptance external-files directory is unavailable",artifacts);
        Files.createDirectories(artifacts.toPath());
        File compileDirectory=Files.createTempDirectory(context.getFilesDir().toPath(),"export-native-").toFile();
        List<X509Certificate> firstSigners=null;
        byte[] firstPayload=null;
        try {
            for(int version=1;version<=2;version++) {
                String message="Vkyra exported native v"+version;
                String sourceText="#include <stdio.h>\nint main() { puts(\""+message+"\"); return 0; }\n";
                File source=new File(compileDirectory,"native-v"+version+".cpp");
                File executable=new File(compileDirectory,"native-v"+version);
                File destination=new File(artifacts,"native-v"+version+".apk");
                Files.write(source.toPath(),sourceText.getBytes(StandardCharsets.UTF_8));
                CppCompiler.CompileResult compiled=CppCompiler.compileToPath(context,source.getPath(),executable.getPath(),false);
                assertTrue(compiled.errorMessage,compiled.success);
                assertEquals(executable.getCanonicalPath(),new File(compiled.outputPath).getCanonicalPath());
                byte[] payload=Files.readAllBytes(executable.toPath());
                assertTrue("Compiler did not produce a complete ELF",payload.length>512);
                assertArrayEquals(new byte[]{0x7f,'E','L','F'},Arrays.copyOf(payload,4));
                ManagedProcessRunner runner=new ManagedProcessRunner(null);
                try {
                    ManagedProcessRunner.Result result=runner.run(new ProcessBuilder(executable.getPath()).directory(compileDirectory),15000);
                    assertEquals(result.errorOutput,0,result.exitCode);
                    assertFalse(result.cancelled);
                    assertEquals(message+"\n",result.output);
                } finally { runner.cancel();runner.awaitStopped(); }

                AppInfoDialog.AppInfo info=new AppInfoDialog.AppInfo(null,"Vkyra Native Acceptance",EXPORTED_PACKAGE,version,version+".0");
                export(context,new ApkExportManager.Request(null,executable,destination,info));
                // Host run-as can read this private copy even when shell's external-storage mount is restricted.
                File hostArtifacts=new File(context.getFilesDir(),"acceptance");
                Files.createDirectories(hostArtifacts.toPath());
                Files.copy(destination.toPath(),new File(hostArtifacts,destination.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ApkVerifier.Result verified=new ApkVerifier.Builder(destination).setMinCheckedPlatformVersion(26).build().verify();
                assertTrue("Exported APK signature did not verify",verified.isVerified());
                assertFalse(verified.getSignerCertificates().isEmpty());
                if(firstSigners==null) firstSigners=verified.getSignerCertificates();
                SignedApkVerifier.verify(destination,firstSigners);

                PackageManager packages=context.getPackageManager();
                PackageInfo metadata=packages.getPackageArchiveInfo(destination.getPath(),PackageManager.GET_ACTIVITIES);
                assertNotNull("Platform could not parse exported APK",metadata);
                assertEquals(EXPORTED_PACKAGE,metadata.packageName);
                assertEquals(version,PackageInfoCompat.getLongVersionCode(metadata));
                assertEquals(version+".0",metadata.versionName);
                assertNotNull(metadata.applicationInfo);
                metadata.applicationInfo.sourceDir=destination.getPath();
                metadata.applicationInfo.publicSourceDir=destination.getPath();
                assertEquals("Vkyra Native Acceptance",packages.getApplicationLabel(metadata.applicationInfo).toString());
                assertNotNull("Export template has no launchable activity declarations",metadata.activities);
                assertTrue(metadata.activities.length>0);
                try(ZipFile archive=new ZipFile(destination)) {
                    assertNotNull(archive.getEntry("AndroidManifest.xml"));
                    assertNotNull("Exported APK lost its compiled resources",archive.getEntry("resources.arsc"));
                    ZipEntry entry=archive.getEntry("assets/bin/executable");
                    assertNotNull("Exported native payload is missing",entry);
                    try(InputStream input=archive.getInputStream(entry);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                        byte[] buffer=new byte[8192];
                        for(int read;(read=input.read(buffer))!=-1;) bytes.write(buffer,0,read);
                        assertArrayEquals("APK native payload differs from the real compiled executable",payload,bytes.toByteArray());
                    }
                }
                if(firstPayload==null) firstPayload=payload;
                else assertFalse("v2 must contain the changed native program",Arrays.equals(firstPayload,payload));
                // Keep sources and binaries alongside APKs so pull/install/upgrade evidence is reproducible.
                Files.write(new File(artifacts,"native-v"+version+".cpp").toPath(),sourceText.getBytes(StandardCharsets.UTF_8));
                Files.write(new File(artifacts,"native-v"+version+".elf").toPath(),payload);
            }
        } finally { removeOwnedDirectory(compileDirectory); }
    }

    private static void export(Context context,ApkExportManager.Request request) throws Exception {
        CountDownLatch finished=new CountDownLatch(1);
        AtomicReference<String> error=new AtomicReference<>();
        AtomicReference<File> result=new AtomicReference<>();
        ApkExportTask task=new ApkExportManager(context).export(request,new ApkExportTask.Listener() {
            @Override public void onProgress(String message,int percent) { assertSame(Looper.getMainLooper(),Looper.myLooper()); }
            @Override public void onSuccess(File output) { result.set(output);finished.countDown(); }
            @Override public void onError(String failure) { error.set(failure);finished.countDown(); }
        });
        try {
            assertTrue("Native APK export timed out",finished.await(90,TimeUnit.SECONDS));
            assertNull(error.get());
            assertNotNull("Native APK export did not return output",result.get());
            assertEquals(request.outputFile,result.get());
            assertTrue(task.awaitFinished(10,TimeUnit.SECONDS));
        } finally {
            task.cancel();
            assertTrue("Export worker did not release its workspace",task.awaitFinished(15,TimeUnit.SECONDS));
        }
    }
    private static void removeOwnedDirectory(File root) throws Exception {
        Files.walkFileTree(root.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path path,java.io.IOException error) throws java.io.IOException {
                if(error!=null) throw error;Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
