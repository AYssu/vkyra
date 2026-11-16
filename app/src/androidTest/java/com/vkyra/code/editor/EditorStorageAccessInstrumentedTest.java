package com.vkyra.code.editor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.vkyra.code.R;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;

/** Legacy runtime grants must not be obscured by an unnecessary all-files modal. */
@RunWith(AndroidJUnit4.class)
public class EditorStorageAccessInstrumentedTest {
    @Test public void runtimeStorageGrantsOpenUsableEditorWithoutAllFilesPermissionModal() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(28, f.context.getApplicationInfo().targetSdkVersion);
            assertEquals(PackageManager.PERMISSION_GRANTED, f.context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE));
            assertEquals(PackageManager.PERMISSION_GRANTED, f.context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
            if (Build.VERSION.SDK_INT >= 30) {
                assertFalse("dedicated fixture proves ordinary grants, without all-files special access", Environment.isExternalStorageManager());
            }
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
                awaitReady(scenario);
                await(scenario, a -> editor(a).isEditable(), "editor layout did not become editable");
                onView(withText("需要存储权限")).check(doesNotExist());
                onView(withId(R.id.code_editor)).check(matches(isDisplayed())).perform(click());
                scenario.onActivity(a -> {
                    assertTrue("actual editor receives the user tap", editor(a).hasFocus());
                    assertEquals(Fixture.SOURCE, editor(a).getText().toString());
                });
                assertEquals(Fixture.SOURCE, read(f.first));
            }
        }
    }
}
