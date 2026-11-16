package com.vkyra.code.signature;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApkSigningAndExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private KeyPair keys;
    private X509Certificate certificate;
    @Before public void identity() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048); keys = generator.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name name = new org.bouncycastle.asn1.x500.X500Name("CN=Export regression only");
        org.bouncycastle.cert.X509v3CertificateBuilder builder = new org.bouncycastle.cert.X509v3CertificateBuilder(name,
                java.math.BigInteger.ONE, new java.util.Date(0), new java.util.Date(4102444800000L), name,
                org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded()));
        certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA").build(keys.getPrivate())));
    }
    @Test public void officialSigningProducesCryptographicallyVerifiedExpectedIdentity() throws Exception {
        File source = unsignedFixture("input.apk");
        CustomApkSigner signer = new CustomApkSigner(keys.getPrivate(), List.of(certificate));
        File signed = signer.signApk(source);
        assertTrue(signer.verifyApk(signed));
        SignedApkVerifier.verify(signed, List.of(certificate));
        byte[] contents = Files.readAllBytes(signed.toPath()); contents[100] ^= 1; Files.write(signed.toPath(), contents);
        assertFalse(signer.verifyApk(signed));
    }
    @Test public void apksigFailureHasNoFallbackAndPreservesPriorOutput() throws Exception {
        File source = temporary.newFile("broken.apk"); Files.writeString(source.toPath(), "not an APK");
        File previous = new File(source.getParentFile(), "signed_broken.apk"); Files.writeString(previous.toPath(), "previous artifact");
        CustomApkSigner signer = new CustomApkSigner(keys.getPrivate(), List.of(certificate));
        try { signer.signApk(source); fail("Signing failure was reported as success"); } catch (Exception expected) { }
        assertEquals("previous artifact", Files.readString(previous.toPath()));
        assertFalse(signer.verifyApk(source)); assertEquals(2, temporary.getRoot().list().length);
    }
    @Test public void cancelledSigningCannotReplacePreviousArtifact() throws Exception {
        File source = unsignedFixture("input.apk");
        File previous = new File(source.getParentFile(), "signed_input.apk"); Files.writeString(previous.toPath(), "old");
        CustomApkSigner signer = new CustomApkSigner(keys.getPrivate(), List.of(certificate));
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        try { signer.signApk(source, () -> checks.incrementAndGet() >= 2); fail(); } catch (IOException expected) { }
        assertEquals("old", Files.readString(previous.toPath()));
        assertFalse(java.util.Arrays.stream(temporary.getRoot().list()).anyMatch(name -> name.startsWith(".signed-")));
    }
    @Test public void publicationFailureAndCancellationKeepOldOutputAndCleanStaging() throws Exception {
        File source = temporary.newFile("source.apk"); Files.writeString(source.toPath(), "candidate");
        File output = temporary.newFile("output.apk"); Files.writeString(output.toPath(), "old");
        try { AtomicArtifactPublisher.publish(source, output, new ExportCancellation(), f -> { throw new IOException("validation failed"); }); fail(); }
        catch (IOException expected) { }
        assertEquals("old", Files.readString(output.toPath()));
        ExportCancellation cancelled = new ExportCancellation();
        try { AtomicArtifactPublisher.publish(source, output, cancelled, f -> cancelled.cancel()); fail(); }
        catch (IOException expected) { }
        assertEquals("old", Files.readString(output.toPath())); assertEquals(2, temporary.getRoot().list().length);
    }
    @Test public void midCopyIoFailureNeverTruncatesExistingOutput() throws Exception {
        File output = temporary.newFile("output.apk"); Files.writeString(output.toPath(), "previous APK");
        InputStream failing = new InputStream() {
            int calls;
            @Override public int read() throws IOException { throw new IOException("storage read failure"); }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                if (calls++ > 0) throw new IOException("storage read failure");
                java.util.Arrays.fill(bytes,offset,offset+16,(byte) 42); return 16;
            }
        };
        try { AtomicArtifactPublisher.publish(failing,output,new ExportCancellation(),f -> fail("Partial data was validated")); fail(); }
        catch (IOException expected) { }
        assertEquals("previous APK",Files.readString(output.toPath())); assertEquals(1,temporary.getRoot().list().length);
    }
    @Test public void twoConcurrentJobsOwnDifferentWorkspacesAndCancelOnlyOne() throws Exception {
        File parent = temporary.newFolder("jobs"), outputA = temporary.newFile("a.apk"), outputB = temporary.newFile("b.apk");
        Files.writeString(outputA.toPath(), "old a"); Files.writeString(outputB.toPath(), "old b");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        java.util.Set<String> directories = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicBoolean staleCallback = new AtomicBoolean();
        LinkedBlockingQueue<File> completed = new LinkedBlockingQueue<>();
        File input = unsignedFixture("unsigned.apk");
        CustomApkSigner signer = new CustomApkSigner(keys.getPrivate(), List.of(certificate));
        ApkExportTask.Operation operation = (workspace, cancellation, progress) -> {
            directories.add(workspace.directory().getPath()); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            cancellation.check(); File local = workspace.file("input.apk"); Files.copy(input.toPath(), local.toPath());
            return signer.signApk(local, cancellation);
        };
        ApkExportTask a = ApkExportTask.submit(parent, outputA, new Callback() {
            public void onSuccess(File f) { staleCallback.set(true); } public void onError(String e) { staleCallback.set(true); }
        }, Runnable::run, operation);
        ApkExportTask b = ApkExportTask.submit(parent, outputB, new Callback() { public void onSuccess(File f) { completed.add(f); } }, Runnable::run, operation);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(2, directories.size()); a.cancel(); release.countDown();
            assertTrue(a.awaitFinished(5, TimeUnit.SECONDS)); assertTrue(b.awaitFinished(5, TimeUnit.SECONDS));
            assertFalse(staleCallback.get()); assertEquals(outputB, completed.poll(1, TimeUnit.SECONDS));
            assertEquals("old a", Files.readString(outputA.toPath())); SignedApkVerifier.verify(outputB, List.of(certificate));
            assertEquals(0, parent.list().length);
        } finally { release.countDown(); a.cancel(); b.cancel(); }
    }
    @Test public void closeSuppressesQueuedTerminalResult() throws Exception {
        File parent = temporary.newFolder("jobs"), output = temporary.newFile("result.apk"), input = unsignedFixture("unsigned.apk");
        LinkedBlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>(); AtomicBoolean called = new AtomicBoolean();
        CustomApkSigner signer = new CustomApkSigner(keys.getPrivate(), List.of(certificate));
        ApkExportTask task = ApkExportTask.submit(parent, output, new Callback() {
            public void onSuccess(File f) { called.set(true); } public void onError(String e) { called.set(true); }
        }, deliveries::add, (work,cancel,progress) -> signer.signApk(input,cancel));
        assertTrue(task.awaitFinished(5,TimeUnit.SECONDS)); task.close();
        for (Runnable delivery; (delivery=deliveries.poll()) != null;) delivery.run(); assertFalse(called.get());
    }
    @Test public void workspaceCleanupNeverFollowsExternalSymlink() throws Exception {
        File parent = temporary.newFolder("jobs"), external = temporary.newFolder("outside");
        Path protectedFile = external.toPath().resolve("keep"); Files.writeString(protectedFile, "keep");
        ExportWorkspace workspace = new ExportWorkspace(parent); Files.createSymbolicLink(workspace.file("link").toPath(), external.toPath());
        workspace.close(); assertEquals("keep", Files.readString(protectedFile)); assertEquals(0, parent.list().length);
    }
    private File unsignedFixture(String name) throws Exception {
        File input = temporary.newFile("fixture-"+System.nanoTime()+".apk");
        try (InputStream resource = getClass().getResourceAsStream("/update/signed-fixture.apk.base64")) {
            assertNotNull(resource); Files.write(input.toPath(), Base64.getMimeDecoder().decode(resource.readAllBytes()));
        }
        File output = temporary.newFile(name);
        try (ZipFile zip = new ZipFile(input); ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml"); stream.putNextEntry(new ZipEntry(entry.getName()));
            try (InputStream contents = zip.getInputStream(entry)) { contents.transferTo(stream); } stream.closeEntry();
        }
        Files.delete(input.toPath()); return output;
    }
    private static class Callback implements ApkExportTask.Listener {
        public void onProgress(String m,int p) { } public void onSuccess(File f) { } public void onError(String error) { throw new AssertionError(error); }
    }
}
