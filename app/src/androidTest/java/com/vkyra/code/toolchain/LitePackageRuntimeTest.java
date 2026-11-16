package com.vkyra.code.toolchain;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vkyra.code.R;
import com.vkyra.code.home.MainActivity;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Run against both packages: Full provides installers; Lite explains recovery without deleting tools. */
@RunWith(AndroidJUnit4.class)
public class LitePackageRuntimeTest {
    @Test public void packageProvidesInstallersOrClearlyExplainsMissingToolsWithoutChangingInstalledData() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(ClangInstaller.getExpectedVersion(context));
        if (ClangInstaller.hasBundledArchive(context)) {
            assertTrue(BuildToolsInstaller.hasArchiveInAssets(context));
            assertTrue(ZshInstaller.hasArchiveInAssets(context));
            return;
        }
        assertFalse(Arrays.stream(context.getAssets().list("")).anyMatch(name -> name.endsWith(".tar.xz")));
        File marker = new File(context.getFilesDir(), ".clang_installed");
        File saved = new File(context.getCacheDir(), "lite-test-clang-marker");
        boolean hadMarker = marker.isFile();
        byte[] previous = hadMarker ? Files.readAllBytes(marker.toPath()) : null;
        if (hadMarker) Files.move(marker.toPath(), saved.toPath());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            onView(withText(R.string.lite_toolchain_required_title)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.lite_toolchain_required_message)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("确定")).inRoot(isDialog()).perform(click());
            assertFalse("Lite must not claim a missing toolchain was installed", marker.exists());
        } finally {
            if (hadMarker) {
                Files.move(saved.toPath(), marker.toPath());
                assertArrayEquals("existing installation marker must be preserved", previous, Files.readAllBytes(marker.toPath()));
            }
        }
    }
}
