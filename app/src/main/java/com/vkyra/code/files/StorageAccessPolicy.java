package com.vkyra.code.files;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import androidx.core.content.ContextCompat;
import java.io.File;

/** Shared-storage permission policy for the retained legacy target and existing grants. */
public final class StorageAccessPolicy {
    private StorageAccessPolicy() {}

    public static boolean usesRuntimeStoragePermissions(Context context) {
        return Build.VERSION.SDK_INT < 30 || context.getApplicationInfo().targetSdkVersion < 30;
    }

    public static boolean hasSharedStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (usesRuntimeStoragePermissions(context)
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true;
            // Preserve compatibility with devices/frameworks that expose real file access
            // while their special-access API reports false.
            try {
                File storage = Environment.getExternalStorageDirectory();
                return storage != null && storage.exists() && storage.canRead() && storage.canWrite()
                        && storage.listFiles() != null;
            } catch (SecurityException denied) { return false; }
        }
        return false;
    }
}
