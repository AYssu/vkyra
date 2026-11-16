package com.vkyra.code.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

/** Verifies signed APK contents, then compares identity against the installed application. */
final class ApkUpdateVerifier {
    static void verify(Context context, File apk) throws Exception {
        if (!apk.isFile() || apk.length() == 0) throw new IOException("安装包不存在或为空");
        Set<String> candidateSigners = verifyContents(apk, Build.VERSION.SDK_INT);
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (candidate == null) throw new IOException("安装包无法解析");
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners()) : installed.signatures;
        Set<String> installedSigners = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) installedSigners.add(digest(signature.toByteArray()));
        ApkIdentityPolicy.validate(context.getPackageName(), version(installed), installedSigners,
                candidate.packageName, version(candidate), candidateSigners);
    }

    static Set<String> verifyContents(File apk, int platformVersion) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(platformVersion)
                .setMaxCheckedPlatformVersion(platformVersion).build().verify();
        if (!result.isVerified()) throw new IOException("安装包签名校验失败");
        Set<String> signers = new HashSet<>();
        for (X509Certificate certificate : result.getSignerCertificates()) signers.add(digest(certificate.getEncoded()));
        if (signers.isEmpty()) throw new IOException("安装包缺少签名证书");
        return signers;
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : Integer.toUnsignedLong(info.versionCode);
    }
    private static String digest(byte[] certificate) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(certificate);
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }
}
