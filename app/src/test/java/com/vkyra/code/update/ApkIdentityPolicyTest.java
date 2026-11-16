package com.vkyra.code.update;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Set;
import org.junit.Test;

public class ApkIdentityPolicyTest {
    @Test public void onlySamePackageSameSigningSetAndHigherVersionPass() throws Exception {
        ApkIdentityPolicy.validate("com.vkyra.code", 10, Set.of("signer"), "com.vkyra.code", 11, Set.of("signer"));
        reject("old.package", 11, Set.of("signer"));
        reject("com.vkyra.code", 11, Set.of("attacker"));
        reject("com.vkyra.code", 11, Set.of());
        reject("com.vkyra.code", 10, Set.of("signer"));
        reject("com.vkyra.code", 9, Set.of("signer"));
        reject("com.vkyra.code", 11, Set.of("signer", "extra"));
    }
    private void reject(String pkg, long version, Set<String> signers) throws Exception {
        try { ApkIdentityPolicy.validate("com.vkyra.code", 10, Set.of("signer"), pkg, version, signers); fail(); }
        catch (IOException expected) { }
    }
}
