package com.vkyra.code.editor;

import android.content.Context;
import android.content.Intent;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.textfield.TextInputEditText;
import com.vkyra.code.R;
import com.vkyra.code.home.MainActivity;
import com.vkyra.code.project.NewProjectActivity;
import com.vkyra.code.project.RecentProjectManager;
import com.vkyra.code.settings.AppPreferences;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;
import static org.junit.Assert.*;

/** Dialog input/confirmation and real filesystem effects, isolated from user projects. */
@RunWith(AndroidJUnit4.class)
public class ProjectFilesUiTest {
    @Test public void newFileDialogCreatesAndOpensFileWhileCancellationCreatesNothing() throws Exception {
        try (Fixture f=new Fixture(); ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            DialogTestUi.awaitActivityFocus(scenario);
            scenario.onActivity(a -> a.findViewById(R.id.btn_new_file).performClick());
            nameDialog("created.cpp", "创建");
            File created=new File(f.project,"created.cpp");
            await(scenario,a -> created.equals(get(a,"currentFile")),"new file was not opened");
            assertTrue(created.isFile());
            assertEquals(0,created.length());
            scenario.onActivity(a -> assertEquals("",editor(a).getText().toString()));
            DialogTestUi.awaitActivityFocus(scenario);
            scenario.onActivity(a -> a.findViewById(R.id.btn_new_file).performClick());
            nameDialog("cancelled.cpp", "取消");
            assertFalse(new File(f.project,"cancelled.cpp").exists());
            assertEquals(Fixture.SOURCE,read(f.first));
        }
    }

    @Test public void fileOptionsRenamePreservesBytesAndDeleteRequiresConfirmation() throws Exception {
        try (Fixture f=new Fixture(); ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            File oldFile=new File(f.project,"unopened.cpp"), renamed=new File(f.project,"renamed.cpp");
            byte[] bytes="// 中文\r\nint untouched;\r\n".getBytes(StandardCharsets.UTF_8);
            Files.write(oldFile.toPath(),bytes);
            options(scenario,oldFile,"重命名");
            nameDialog("renamed.cpp","确定");
            awaitFileOperation(scenario,() -> renamed.isFile() && !oldFile.exists());
            assertFalse(oldFile.exists());
            assertArrayEquals(bytes,Files.readAllBytes(renamed.toPath()));
            options(scenario,renamed,"删除");
            onView(withText("取消")).inRoot(isDialog()).perform(click());
            assertArrayEquals(bytes,Files.readAllBytes(renamed.toPath()));
            options(scenario,renamed,"删除");
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            awaitFileOperation(scenario,() -> !renamed.exists());
            assertFalse(renamed.exists());
            assertEquals(Fixture.SOURCE,read(f.first));
        }
    }

    @Test public void folderCreationRenameAndRecursiveDeleteKeepSiblingFiles() throws Exception {
        try (Fixture f=new Fixture(); ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            DialogTestUi.awaitActivityFocus(scenario);
            scenario.onActivity(a -> a.findViewById(R.id.btn_new_folder).performClick());
            nameDialog("assets","创建");
            File oldDir=new File(f.project,"assets"), renamed=new File(f.project,"resources");
            assertTrue(oldDir.isDirectory());
            File nested=new File(oldDir,"deep/sub");
            assertTrue(nested.mkdirs());
            Files.write(new File(nested,"keep.txt").toPath(),new byte[]{2,3,5});
            options(scenario,oldDir,"重命名");
            nameDialog("resources","确定");
            awaitFileOperation(scenario,() -> renamed.isDirectory() && !oldDir.exists());
            assertFalse(oldDir.exists());
            assertArrayEquals(new byte[]{2,3,5},Files.readAllBytes(new File(renamed,"deep/sub/keep.txt").toPath()));
            options(scenario,renamed,"删除");
            onView(withText("取消")).inRoot(isDialog()).perform(click());
            assertTrue(new File(renamed,"deep/sub/keep.txt").isFile());
            options(scenario,renamed,"删除");
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            awaitFileOperation(scenario,() -> !renamed.exists());
            assertFalse(renamed.exists());
            assertEquals(Fixture.SOURCE,read(f.first));
        }
    }

    @Test public void projectCreateButtonProducesEveryTemplateAndHonorsGitReadmeChoices() throws Exception {
        try (ProjectRoot f=new ProjectRoot()) {
            int[] radios={R.id.rb_empty,R.id.rb_cpp,R.id.rb_c,R.id.rb_ndk,R.id.rb_make,R.id.rb_cmake};
            String[] templates={"empty","cpp","c","ndk","make","cmake"};
            String[][] outputs={{},{"main.cpp"},{"main.c"},{"jni/main.cpp","jni/Android.mk","jni/Application.mk"},
                    {"main.cpp","Makefile"},{"main.cpp","CMakeLists.txt"}};
            for(int i=0;i<radios.length;i++) {
                String name="Ui_"+templates[i];int radio=radios[i];boolean extras=i==1;
                try(ActivityScenario<NewProjectActivity> scenario=ActivityScenario.launch(new Intent(f.context,NewProjectActivity.class))) {
                    configureProject(scenario,name,radio,extras);
                    onView(withId(R.id.btn_create)).perform(scrollTo(),closeSoftKeyboard(),click());
                    File project=new File(f.root,name);
                    awaitProjectCreation(project,extras);
                    onView(withText("创建成功")).inRoot(isDialog()).check(matches(isDisplayed()));
                    assertTrue(project.isDirectory());
                    JSONObject config=new JSONObject(read(new File(project,".akode/project.json")));
                    assertEquals(name,config.getString("name"));
                    assertEquals(templates[i],config.getString("template"));
                    for(String relative:outputs[i]) {
                        File output=new File(project,relative);
                        assertTrue(relative,output.isFile());
                        assertTrue(relative,output.length()>0);
                    }
                    assertEquals(extras,new File(project,"README.md").isFile());
                    assertEquals(extras,new File(project,".git/HEAD").isFile());
                    if(i==1) {
                        assertTrue(read(new File(project,"main.cpp")).contains("Hello, "+name));
                        assertTrue(read(new File(project,".gitignore")).contains(".akode/"));
                    }
                    if(i==5) assertTrue(read(new File(project,"CMakeLists.txt")).contains("add_executable(${PROJECT_NAME} main.cpp)"));
                    onView(withText("关闭")).inRoot(isDialog()).perform(click());
                }
            }
        }
    }

    @Test public void duplicateInvalidAndEmptyProjectNamesCannotMutateExistingFiles() throws Exception {
        try (ProjectRoot f=new ProjectRoot()) {
            File existing=new File(f.root,"Existing");assertTrue(existing.mkdir());
            File marker=new File(existing,"main.cpp");Files.write(marker.toPath(),new byte[]{8,6,4});
            try(ActivityScenario<NewProjectActivity> scenario=ActivityScenario.launch(new Intent(f.context,NewProjectActivity.class))) {
                configureProject(scenario,"Existing",R.id.rb_cpp,true);
                onView(withId(R.id.btn_create)).perform(scrollTo(),click());
                assertArrayEquals(new byte[]{8,6,4},Files.readAllBytes(marker.toPath()));
                assertFalse(new File(existing,".akode").exists());
                onView(withId(R.id.et_project_name)).perform(scrollTo(),replaceText("bad/name"),closeSoftKeyboard());
                onView(withId(R.id.btn_create)).perform(scrollTo(),click());
                assertFalse(new File(f.root,"bad").exists());
                onView(withId(R.id.et_project_name)).perform(scrollTo(),replaceText(""),closeSoftKeyboard());
                onView(withId(R.id.btn_create)).perform(scrollTo(),click());
                assertArrayEquals(new String[]{"Existing"},f.root.list());
                scenario.onActivity(a -> assertFalse(a.isFinishing()));
            }
        }
    }

    @Test public void projectDeleteRejectsWrongNameThenConfigOnlyPreservesSources() throws Exception {
        try(ProjectRoot f=new ProjectRoot()) {
            File project=f.project("ConfigOnly");AtomicBoolean completed=new AtomicBoolean();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(f.context,MainActivity.class))) {
                showProjectDelete(scenario,project,completed);
                nameDialog("wrong","删除");
                assertFalse(completed.get());
                assertTrue(new File(project,".akode/project.json").isFile());
                onView(withText("删除项目")).inRoot(isDialog()).check(matches(isDisplayed()));
                nameDialog(project.getName(),"删除");
                assertTrue(completed.get());
                assertFalse(new File(project,".akode").exists());
                assertEquals("int preserved;",read(new File(project,"main.cpp")));
                assertEquals("sibling",read(f.sibling));
            }
        }
    }

    @Test public void wholeProjectDeleteCanBeCancelledThenDeletesOnlyNamedProject() throws Exception {
        try(ProjectRoot f=new ProjectRoot()) {
            File project=f.project("DeleteAll");AtomicBoolean completed=new AtomicBoolean();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(f.context,MainActivity.class))) {
                showProjectDelete(scenario,project,completed);
                onView(withText("删除整个项目文件夹")).inRoot(isDialog()).perform(click());
                nameDialog(project.getName(),"取消");
                assertFalse(completed.get());
                assertTrue(new File(project,"main.cpp").isFile());
                showProjectDelete(scenario,project,completed);
                onView(withText("删除整个项目文件夹")).inRoot(isDialog()).perform(click());
                nameDialog(project.getName(),"删除");
                assertTrue(completed.get());
                assertFalse(project.exists());
                assertEquals("sibling",read(f.sibling));
            }
        }
    }

    private static void options(ActivityScenario<EditorActivity> scenario,File target,String action) {
        // Same entry as explorer long-press; subsequent option and confirmation clicks are real UI.
        DialogTestUi.awaitActivityFocus(scenario);
        scenario.onActivity(a -> call(a,"showFileOptionsDialog",new Class[]{File.class},target));
        onView(withText(action)).inRoot(isDialog()).perform(click());
    }
    private static void awaitFileOperation(ActivityScenario<EditorActivity> scenario,java.util.function.BooleanSupplier result) throws Exception {
        await(scenario,a -> !Boolean.TRUE.equals(get(a,"explorerFileOperationRunning")) && result.getAsBoolean(),
                "filesystem operation did not complete successfully");
    }
    private static void nameDialog(String name,String button) {
        onView(isAssignableFrom(TextInputEditText.class)).inRoot(isDialog()).perform(replaceText(name),closeSoftKeyboard());
        onView(withText(button)).inRoot(isDialog()).perform(click());
    }
    private static void configureProject(ActivityScenario<NewProjectActivity> scenario,String name,int template,boolean extras) throws Exception {
        // NewProject deliberately posts its initial IME request at 200 ms. Wait for that startup
        // task before closing the IME, otherwise it can reopen between scrolling and the tap.
        java.util.concurrent.CountDownLatch initialInputReady=new java.util.concurrent.CountDownLatch(1);
        scenario.onActivity(a -> a.findViewById(R.id.et_project_name).postDelayed(initialInputReady::countDown,250));
        assertTrue("initial project input did not settle",initialInputReady.await(3,java.util.concurrent.TimeUnit.SECONDS));
        onView(withId(R.id.et_project_name)).perform(replaceText(name),closeSoftKeyboard());
        scenario.onActivity(a -> {
            ((RadioGroup)a.findViewById(R.id.rg_template)).check(template);
            ((android.widget.CompoundButton)a.findViewById(R.id.switch_git_init)).setChecked(extras);
            ((android.widget.CompoundButton)a.findViewById(R.id.switch_readme)).setChecked(extras);
            assertTrue(((TextView)a.findViewById(R.id.tv_project_path)).getText().toString().endsWith("/"+name));
        });
    }
    private static void awaitProjectCreation(File project,boolean extras) throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+10_000;
        while (android.os.SystemClock.uptimeMillis()<deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if(new File(project,".akode/project.json").isFile()
                    && (!extras || new File(project,".git/HEAD").isFile())) return;
            android.os.SystemClock.sleep(20);
        }
        fail("Create button did not finish creating project metadata: "+project.getName());
    }
    private static void showProjectDelete(ActivityScenario<MainActivity> scenario,File project,AtomicBoolean completed) {
        DialogTestUi.awaitActivityFocus(scenario);
        scenario.onActivity(a -> call(a,"showDeleteProjectDialog",new Class[]{RecentProjectManager.ProjectInfo.class,Runnable.class},
                new RecentProjectManager.ProjectInfo(project.getName(),project.getAbsolutePath()),(Runnable)() -> completed.set(true)));
    }

    private static final class ProjectRoot implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final PrefsBackup settings=new PrefsBackup(context,AppPreferences.PREFS_NAME,"project_root");
        final File root,sibling,global=new File(context.getFilesDir(),RecentProjectManager.GLOBAL_PROJECTS_FILE);
        final byte[] globalBefore;
        ProjectRoot() throws Exception {
            globalBefore=global.isFile()?Files.readAllBytes(global.toPath()):null;
            root=Files.createTempDirectory(context.getCacheDir().toPath(),"project-ui-").toFile();
            sibling=new File(root,"sibling.txt");
            // Only deletion tests use the sibling; template/root listing assertions need an empty root.
            assertTrue(settings.prefs.edit().putString("project_root",root.getPath()).commit());
        }
        File project(String name) throws Exception {
            File project=new File(root,name);assertTrue(project.mkdir());
            Files.write(new File(project,"main.cpp").toPath(),"int preserved;".getBytes(StandardCharsets.UTF_8));
            Files.write(sibling.toPath(),"sibling".getBytes(StandardCharsets.UTF_8));
            RecentProjectManager.initProject(project.getPath(),name,"cpp","main.cpp");
            return project;
        }
        @Override public void close() throws Exception {
            settings.close();
            if(globalBefore==null) Files.deleteIfExists(global.toPath()); else Files.write(global.toPath(),globalBefore);
            removeTree(root);
        }
    }
}
