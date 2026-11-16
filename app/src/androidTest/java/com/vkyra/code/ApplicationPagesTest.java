package com.vkyra.code;

import com.vkyra.code.home.MainActivity;
import com.vkyra.code.plugin.PluginActivity;
import com.vkyra.code.project.NewProjectActivity;
import com.vkyra.code.settings.SettingsActivity;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApplicationPagesTest {
    @Test public void mainSettingsPluginsAndNewProjectSurviveRecreation() {
        checkPage(MainActivity.class);
        checkPage(SettingsActivity.class);
        checkPage(PluginActivity.class);
        checkPage(NewProjectActivity.class);
    }
    @Test public void delayedClangVersionResultsIgnoreDestroyedHomeAndStillUpdateCurrentHome() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Method delivery = MainActivity.class.getDeclaredMethod("applyClangVersionResult",
                View.class, TextView.class, String.class, String.class);
        delivery.setAccessible(true);
        AtomicReference<MainActivity> previous = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(previous::set);
            scenario.recreate();
            scenario.onActivity(current -> {
                MainActivity destroyed = previous.get();
                assertTrue("recreation must destroy the old window owner", destroyed.isDestroyed());
                TextView status = new TextView(current);
                status.setText("unchanged");
                try {
                    // Deliver both outcomes only after the original Activity has been destroyed.
                    delivery.invoke(destroyed, null, status, "clang version test", "clang version test");
                    assertEquals("late success must not update a destroyed page", "unchanged", status.getText().toString());
                    delivery.invoke(destroyed, null, status, "clang version test", null);
                    assertEquals("late failure must not update or open a dialog on a destroyed page", "unchanged", status.getText().toString());
                    delivery.invoke(current, null, status, "clang version test", "clang version test");
                    assertEquals("the current page must still receive results", "已就绪", status.getText().toString());
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        }
    }
    private static <T extends AppCompatActivity> void checkPage(Class<T> page) {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(ActivityScenario<T> scenario=ActivityScenario.launch(new Intent(context,page))) {
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(android.R.id.content)));
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
    }
    @Test public void fileProviderOnlyExposesExplicitShareAndUpdateSnapshots() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String authority=context.getPackageName()+".fileprovider";
        File shared=new File(context.getCacheDir(),"shared/contract.txt");
        Files.createDirectories(shared.getParentFile().toPath());Files.write(shared.toPath(),new byte[]{1});
        assertEquals("content",FileProvider.getUriForFile(context,authority,shared).getScheme());
        for(File denied:new File[]{new File(context.getFilesDir(),"credential-store"),new File(context.getCacheDir(),"private-draft"),new File("/storage/emulated/0/project.cpp")}) {
            try { FileProvider.getUriForFile(context,authority,denied);fail("non-share file was exposed"); }
            catch(IllegalArgumentException expected) { }
        }
    }
}
