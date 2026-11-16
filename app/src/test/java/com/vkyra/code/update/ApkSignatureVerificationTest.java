package com.vkyra.code.update;


import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApkSignatureVerificationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void realV2SignedApkVerifiesAtMinAndModernPlatform() throws Exception {
        File apk = fixture();
        Set<String> minSigners = ApkUpdateVerifier.verifyContents(apk, 26);
        assertEquals(1, minSigners.size());
        assertEquals(minSigners, ApkUpdateVerifier.verifyContents(apk, 35));
    }
    @Test public void changingSignedApkBytesCannotPassVerification() throws Exception {
        File apk = fixture(); byte[] bytes = Files.readAllBytes(apk.toPath());
        // A byte in AndroidManifest.xml's stored ZIP payload, with the original signing block intact.
        bytes[100] ^= 1; Files.write(apk.toPath(), bytes);
        try { ApkUpdateVerifier.verifyContents(apk, 26); fail("Tampered APK accepted"); }
        catch (Exception expected) { }
    }
    @Test public void validArchiveWithoutSigningBlockCannotPassVerification() throws Exception {
        File signed = fixture(), unsigned = temp.newFile("unsigned.apk");
        try (ZipFile input = new ZipFile(signed); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(unsigned.toPath()))) {
            ZipEntry manifest = input.getEntry("AndroidManifest.xml");
            output.putNextEntry(new ZipEntry(manifest.getName()));
            try (InputStream stream = input.getInputStream(manifest)) { stream.transferTo(output); }
            output.closeEntry();
        }
        try { ApkUpdateVerifier.verifyContents(unsigned, 26); fail("Unsigned APK accepted"); }
        catch (Exception expected) { }
    }
    private File fixture() throws Exception {
        File apk = temp.newFile("signed.apk");
        try (InputStream resource = getClass().getResourceAsStream("/update/signed-fixture.apk.base64")) {
            assertNotNull(resource);
            Files.write(apk.toPath(), Base64.getMimeDecoder().decode(resource.readAllBytes()));
        }
        return apk;
    }
}
