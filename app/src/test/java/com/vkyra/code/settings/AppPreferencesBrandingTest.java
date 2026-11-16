package com.vkyra.code.settings;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppPreferencesBrandingTest {
    @Test public void freshDefaultUsesVkyraAndExplicitExistingProjectRootsRemainUnchanged() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(AppPreferences.KEY_PROJECT_ROOT).commit();
        assertEquals("/storage/emulated/0/Vkyra", AppPreferences.getProjectRoot(context));
        AppPreferences.setProjectRoot(context, "/storage/emulated/0/Akode");
        assertEquals("/storage/emulated/0/Akode", AppPreferences.getProjectRoot(context));
        AppPreferences.setProjectRoot(context, "/storage/emulated/0/MyProjects");
        assertEquals("/storage/emulated/0/MyProjects", AppPreferences.getProjectRoot(context));
    }
}
