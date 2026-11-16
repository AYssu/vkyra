package com.vkyra.code.home;

import android.Manifest;
import android.content.pm.PackageManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Permission callbacks are tested before onCreate to expose accidental initialization. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {26, 30})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainStoragePermissionTest {
    private static final String[] PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    @Test public void partialGrantCannotInitializeHomeEvenWhenCurrentPermissionChecksPass() throws Exception {
        MainActivity activity = attachedWithRealPermissionChecksGranted();
        activity.onRequestPermissionsResult(1002, PERMISSIONS,
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED});
        assertRejected(activity);
    }

    @Test public void emptyCanceledResultCannotInitializeHome() throws Exception {
        MainActivity activity = attachedWithRealPermissionChecksGranted();
        activity.onRequestPermissionsResult(1002, PERMISSIONS, new int[0]);
        assertRejected(activity);
    }

    @Test public void truncatedResultCannotTreatFirstGrantedPermissionAsCompleteAuthorization() throws Exception {
        MainActivity activity = attachedWithRealPermissionChecksGranted();
        activity.onRequestPermissionsResult(1002, PERMISSIONS, new int[]{PackageManager.PERMISSION_GRANTED});
        assertRejected(activity);
    }

    private static MainActivity attachedWithRealPermissionChecksGranted() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(PERMISSIONS);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        for (String permission : PERMISSIONS) {
            assertEquals(PackageManager.PERMISSION_GRANTED, activity.checkSelfPermission(permission));
        }
        return activity;
    }
    private static void assertRejected(MainActivity activity) throws Exception {
        assertTrue("incomplete permission callback must finish the unauthorized home", activity.isFinishing());
        Field field = MainActivity.class.getDeclaredField("editorModeCard");
        field.setAccessible(true);
        assertNull("feature listeners must never initialize from incomplete permission results", field.get(activity));
    }
}
