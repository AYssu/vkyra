package com.vkyra.code.update;

import java.io.IOException;
import java.util.Set;

/** Fail closed; certificate rotation requires a separate verified lineage migration. */
final class ApkIdentityPolicy {
    static void validate(String installedPackage, long installedVersion, Set<String> installedSigners,
                         String candidatePackage, long candidateVersion, Set<String> candidateSigners) throws IOException {
        if (!installedPackage.equals(candidatePackage)) throw new IOException("安装包不属于 Vkyra");
        if (candidateVersion <= installedVersion) throw new IOException("安装包版本必须高于当前版本");
        if (installedSigners == null || installedSigners.isEmpty() || candidateSigners == null
                || !installedSigners.equals(candidateSigners)) throw new IOException("安装包签名与当前应用不一致");
    }
}
