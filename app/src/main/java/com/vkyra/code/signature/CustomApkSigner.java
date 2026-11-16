package com.vkyra.code.signature;

import android.content.Context;
import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Official apksig signing only; signing or cryptographic verification failure is fatal. */
public class CustomApkSigner {
    private static final Object KEYSTORE_LOCK = new Object();
    // Keep the existing local export signing identity readable for upgrades of exported applications.
    private static final char[] LEGACY_PASSWORD = "123456".toCharArray();
    private static final String KEY_ALIAS = "testkey";
    private final File keystoreFile;
    private final Context application;
    private final SigningIdentity suppliedIdentity;

    public CustomApkSigner(Context context) {
        application = context.getApplicationContext();
        keystoreFile = new File(application.getFilesDir(), "test.keystore");
        suppliedIdentity = null;
    }

    CustomApkSigner(PrivateKey key, List<X509Certificate> certificates) {
        application = null;
        keystoreFile = null;
        suppliedIdentity = new SigningIdentity(key, certificates);
    }

    public void generateTestKeystore() throws Exception {
        if (suppliedIdentity != null) return;
        synchronized (KEYSTORE_LOCK) {
            if (keystoreFile.exists()) return;
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, LEGACY_PASSWORD);
            new KeyPairGenerator(application).generateKeyPair(store, KEY_ALIAS, new String(LEGACY_PASSWORD));
            File stage = File.createTempFile(".export-key-", ".p12", keystoreFile.getParentFile());
            try {
                if (!stage.setReadable(false, false) || !stage.setWritable(false, false)
                        || !stage.setReadable(true, true) || !stage.setWritable(true, true))
                    throw new IOException("Cannot secure export keystore");
                try (FileOutputStream output = new FileOutputStream(stage)) {
                    store.store(output, LEGACY_PASSWORD);
                    output.flush(); output.getFD().sync();
                }
                Files.move(stage.toPath(), keystoreFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(stage.toPath()); }
        }
    }

    public File signApk(File unsignedApk) throws Exception { return signApk(unsignedApk, () -> false); }

    File signApk(File unsignedApk, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        SigningIdentity identity = identity();
        if (identity.key == null || identity.certificates.isEmpty()) throw new IOException("Export signing key is unavailable");
        File output = new File(unsignedApk.getParentFile(), "signed_" + unsignedApk.getName());
        File stage = File.createTempFile(".signed-", ".apk", unsignedApk.getParentFile());
        try {
            ApkSigner.SignerConfig configuration = new ApkSigner.SignerConfig.Builder(
                    "CERT", identity.key, identity.certificates).build();
            new ApkSigner.Builder(Collections.singletonList(configuration))
                    .setInputApk(unsignedApk).setOutputApk(stage).setMinSdkVersion(26)
                    .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false)
                    .setV4SigningEnabled(false).build().sign();
            checkCancelled(cancelled);
            SignedApkVerifier.verify(stage, identity.certificates);
            checkCancelled(cancelled);
            try (FileOutputStream sync = new FileOutputStream(stage, true)) { sync.getFD().sync(); }
            Files.move(stage.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return output;
        } finally { Files.deleteIfExists(stage.toPath()); }
    }

    private SigningIdentity identity() throws Exception {
        if (suppliedIdentity != null) return suppliedIdentity;
        generateTestKeystore();
        synchronized (KEYSTORE_LOCK) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (FileInputStream input = new FileInputStream(keystoreFile)) { store.load(input, LEGACY_PASSWORD); }
            PrivateKey key = (PrivateKey) store.getKey(KEY_ALIAS, LEGACY_PASSWORD);
            Certificate[] chain = store.getCertificateChain(KEY_ALIAS);
            List<X509Certificate> certificates = new ArrayList<>();
            if (chain != null) for (Certificate certificate : chain) {
                if (!(certificate instanceof X509Certificate)) throw new IOException("Unsupported signing certificate");
                certificates.add((X509Certificate) certificate);
            }
            return new SigningIdentity(key, certificates);
        }
    }

    public boolean verifyApk(File apk) {
        try { SignedApkVerifier.verify(apk, null); return true; }
        catch (Exception failure) { return false; }
    }
    public File getKeystoreFile() { return keystoreFile; }
    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled");
    }
    private static final class SigningIdentity {
        final PrivateKey key; final List<X509Certificate> certificates;
        SigningIdentity(PrivateKey key, List<X509Certificate> certificates) {
            this.key = key; this.certificates = Collections.unmodifiableList(new ArrayList<>(certificates));
        }
    }
}
