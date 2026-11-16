package com.vkyra.code.git;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GitCredentialManagerInstrumentedTest {
    @Test public void keystoreStorageAndOriginMatchingPreserveAccountBoundaries() {
        Context application = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "vkyra-credential-test-" + UUID.randomUUID() + "-";
        Context isolated = new ContextWrapper(application) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            assertTrue(GitCredentialManager.saveCredential(isolated, new GitCredentialManager.Credential(
                    "Test credential", "https://github.com:8443", "test-account", "TEST_ONLY_DUMMY_TOKEN")));
            assertEquals(1, GitCredentialManager.getCredentials(isolated).size());
            assertNotNull(GitCredentialManager.findCredentialForUrl(isolated, "https://github.com:8443/repo.git"));
            assertNull(GitCredentialManager.findCredentialForUrl(isolated, "https://github.com/repo.git"));
            assertNull(GitCredentialManager.findCredentialForUrl(isolated, "http://github.com:8443/repo.git"));
            assertNull(GitCredentialManager.findCredentialForUrl(isolated, "https://github.com.attacker.invalid:8443/repo.git"));
            assertNull(GitCredentialManager.findCredentialForUrl(isolated, "https://other-account@github.com:8443/repo.git"));
            String stored = isolated.getSharedPreferences("GitCredentials", Context.MODE_PRIVATE)
                    .getString("credentials", "");
            assertFalse(stored.contains("TEST_ONLY_DUMMY_TOKEN"));
            assertFalse(stored.contains("test-account"));
            assertFalse(GitCredentialManager.saveCredential(isolated, new GitCredentialManager.Credential(
                    "Invalid credential", "", "test-account", "TEST_ONLY_DUMMY_TOKEN")));
            assertEquals(1, GitCredentialManager.getCredentials(isolated).size());

            SharedPreferences prefs = isolated.getSharedPreferences("GitCredentials", Context.MODE_PRIVATE);
            assertTrue(prefs.edit().putString("credentials", "TEST_ONLY_CORRUPT_CIPHERTEXT").commit());
            assertTrue(GitCredentialManager.getCredentials(isolated).isEmpty());
            assertFalse(GitCredentialManager.saveCredential(isolated, new GitCredentialManager.Credential(
                    "Replacement", "https://github.com", "other-account", "TEST_ONLY_DUMMY_TOKEN")));
            assertEquals("TEST_ONLY_CORRUPT_CIPHERTEXT", prefs.getString("credentials", ""));
            assertTrue(prefs.edit().putString("credentials", stored).commit());
            assertTrue(GitCredentialManager.deleteCredential(isolated, "https://GITHUB.com:8443", "test-account"));
            assertTrue(GitCredentialManager.getCredentials(isolated).isEmpty());
        } finally {
            assertTrue(GitCredentialManager.clearAll(isolated));
        }
    }

    @Test public void deniedCredentialStorageReportsFailure() {
        Context application = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context denied = new ContextWrapper(application) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new SecurityException("TEST_ONLY_STORAGE_DENIAL");
            }
        };
        assertFalse(GitCredentialManager.saveCredential(denied, new GitCredentialManager.Credential(
                "Test", "https://github.com", "test-account", "TEST_ONLY_DUMMY_TOKEN")));
        assertFalse(GitCredentialManager.deleteCredential(denied, "https://github.com", "test-account"));
        assertFalse(GitCredentialManager.clearAll(denied));
    }
}
