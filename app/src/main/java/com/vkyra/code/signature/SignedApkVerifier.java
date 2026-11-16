package com.vkyra.code.signature;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/** A successful ZIP write is never evidence of a valid APK signature. */
final class SignedApkVerifier {
    static void verify(File apk, List<X509Certificate> expectedSigners) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().isEmpty())
            throw new IOException("APK cryptographic signature verification failed");
        if (expectedSigners != null) {
            if (expectedSigners.size() != result.getSignerCertificates().size())
                throw new IOException("Unexpected APK signing identity");
            for (X509Certificate expected : expectedSigners) {
                boolean found = false;
                for (X509Certificate actual : result.getSignerCertificates())
                    if (Arrays.equals(expected.getEncoded(), actual.getEncoded())) { found = true; break; }
                if (!found) throw new IOException("Unexpected APK signing identity");
            }
        }
    }
}
