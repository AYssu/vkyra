package com.vkyra.code.settings;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, manifest = Config.NONE)
public class AppPreferencesTest {
    private Context context;
    @Before public void reset() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void legacyIntegerFontPreferenceMigratesWithoutReset() {
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(AppPreferences.KEY_FONT_SIZE, 31).commit();
        assertEquals(31, AppPreferences.getFontSize(context));
        assertEquals("31", context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AppPreferences.KEY_FONT_SIZE, null));
    }

    @Test public void settingDefaultsPreservesUserSettings() {
        AppPreferences.setFontSize(context, 18);
        AppPreferences.setEditorTheme(context, "light");
        AppPreferences.setAutoSave(context, false);
        AppPreferences.ensureDefaultSettings(context);
        assertEquals(18, AppPreferences.getFontSize(context));
        assertEquals("light", AppPreferences.getEditorTheme(context));
        assertFalse(AppPreferences.getAutoSave(context));
    }
}
