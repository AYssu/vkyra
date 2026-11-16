package com.vkyra.code.files;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowEnvironment;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {26, 30}, shadows = StorageAccessPolicyTest.NoAllFilesAccess.class)
public class StorageAccessPolicyTest {
    /** Fixture models only the platform's special-access state, not application policy. */
    @Implements(Environment.class)
    public static class NoAllFilesAccess extends ShadowEnvironment {
        @Implementation(minSdk = 30)
        protected static boolean isExternalStorageManager() { return false; }
    }
    private Context context;
    @Before public void denyStorageWithoutPhysicalAccessFallback() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 28;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        java.io.File external = Files.createTempDirectory("storage-policy-").resolve("absent").toFile();
        ShadowEnvironment.setExternalStorageDirectory(external.toPath());
    }
    @Test public void bothLegacyGrantsAuthorizeAccessWithoutAllFilesPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertTrue(StorageAccessPolicy.usesRuntimeStoragePermissions(context));
        assertTrue(StorageAccessPolicy.hasSharedStorageAccess(context));
    }
    @Test public void deniedOrPartialLegacyGrantsDoNotAuthorizeSharedStorage() {
        assertFalse(StorageAccessPolicy.hasSharedStorageAccess(context));
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertFalse(StorageAccessPolicy.hasSharedStorageAccess(context));
    }
    @Test public void specialSettingsRouteIsReservedForModernTargetsOnModernAndroid() {
        context.getApplicationInfo().targetSdkVersion = 30;
        assertEquals(Build.VERSION.SDK_INT < 30, StorageAccessPolicy.usesRuntimeStoragePermissions(context));
    }
}
