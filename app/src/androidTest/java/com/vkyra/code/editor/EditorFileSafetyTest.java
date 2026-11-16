package com.vkyra.code.editor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.textfield.TextInputEditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorFileSafetyTest {
    @Test public void openedFileRenameAndDeleteAreRejectedWithoutLosingItsDirtyBuffer() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            String draft="int keep_unsaved = 42;";
            scenario.onActivity(a -> editor(a).setText(draft));
            for(String action:new String[]{"重命名","删除"}) {
                options(scenario,f.first,action);
                blockedPrompt();
                assertEquals(Fixture.SOURCE,read(f.first));
                scenario.onActivity(a -> {
                    assertEquals(f.first,get(a,"currentFile"));
                    assertEquals(draft,editor(a).getText().toString());
                    assertTrue((Boolean)get(a,"isModified"));
                    assertEquals(f.first.getPath(),((EditorSessionViewModel)get(a,"editorSession")).activeDocument().filePath);
                });
            }
        }
    }

    @Test public void directoryContainingInactiveDirtyTabCannotBeRenamedOrDeleted() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            File directory=new File(f.project,"sources");assertTrue(directory.mkdir());
            File nested=new File(directory,"nested.cpp");
            Files.write(nested.toPath(),"int disk;".getBytes(StandardCharsets.UTF_8));
            scenario.onActivity(a -> call(a,"doOpenFile",new Class[]{File.class},nested));
            await(scenario,a -> nested.equals(get(a,"currentFile")),"nested file did not open");
            scenario.onActivity(a -> {
                editor(a).setText("int unsaved_nested;");
                EditorSessionViewModel session=(EditorSessionViewModel)get(a,"editorSession");
                int original=-1;
                for(int i=0;i<session.documents.size();i++) {
                    if(f.first.getPath().equals(session.documents.get(i).filePath)) original=i;
                }
                assertTrue("original tab is missing",original>=0);
                call(a,"switchToFile",new Class[]{int.class},original);
                assertEquals(f.first,get(a,"currentFile"));
            });
            for(String action:new String[]{"重命名","删除"}) {
                options(scenario,directory,action);
                blockedPrompt();
                assertEquals("int disk;",read(nested));
                scenario.onActivity(a -> {
                    EditorSessionViewModel session=(EditorSessionViewModel)get(a,"editorSession");
                    EditorDocument document=null;
                    for(EditorDocument candidate:session.documents) {
                        if(nested.getPath().equals(candidate.filePath)) document=candidate;
                    }
                    assertNotNull("nested tab is missing",document);
                    assertEquals(nested.getPath(),document.filePath);
                    assertEquals("int unsaved_nested;",document.content);
                    assertTrue(document.isModified);
                    assertEquals(f.first,get(a,"currentFile"));
                });
            }
        }
    }

    @Test public void recursiveExplorerDeleteRemovesSymlinkEntryAndPreservesExternalTarget() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            File outside=Files.createTempDirectory(f.context.getCacheDir().toPath(),"delete-target-").toFile();
            try {
                File sentinel=new File(outside,"sentinel.cpp");byte[] original={5,3,1};Files.write(sentinel.toPath(),original);
                File selected=new File(f.project,"unopened-tree");assertTrue(selected.mkdir());
                Files.createSymbolicLink(new File(selected,"external-link").toPath(),outside.toPath());
                Files.createSymbolicLink(new File(selected,"dangling-link").toPath(),new File(outside,"absent").toPath());
                options(scenario,selected,"删除");
                onView(withText("删除")).inRoot(isDialog()).perform(click());
                await(scenario,a -> !Boolean.TRUE.equals(get(a,"explorerFileOperationRunning"))
                        && !Files.exists(selected.toPath(),LinkOption.NOFOLLOW_LINKS),"recursive delete did not finish");
                assertArrayEquals(original,Files.readAllBytes(sentinel.toPath()));
                assertEquals(Fixture.SOURCE,read(f.first));
            } finally { removeTree(outside); }
        }
    }

    @Test public void invalidRenameAndExistingDestinationPreserveSourceDestinationAndActiveDocument() throws Exception {
        try(Fixture f=new Fixture();ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            File source=new File(f.project,"unopened.cpp"),destination=new File(f.project,"exists.cpp");
            Files.write(source.toPath(),new byte[]{1,2});Files.write(destination.toPath(),new byte[]{9,8});
            options(scenario,source,"重命名");
            rename("../escape.cpp");
            assertArrayEquals(new byte[]{1,2},Files.readAllBytes(source.toPath()));
            assertFalse(new File(f.project.getParentFile(),"escape.cpp").exists());
            options(scenario,source,"重命名");
            rename("exists.cpp");
            await(scenario,a -> !Boolean.TRUE.equals(get(a,"explorerFileOperationRunning")),"failed rename did not return");
            onView(withText("文件操作失败")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("确定")).inRoot(isDialog()).perform(click());
            assertArrayEquals(new byte[]{1,2},Files.readAllBytes(source.toPath()));
            assertArrayEquals(new byte[]{9,8},Files.readAllBytes(destination.toPath()));
            scenario.onActivity(a -> {
                assertEquals(f.first,get(a,"currentFile"));
                assertEquals(Fixture.SOURCE,editor(a).getText().toString());
            });
        }
    }

    private static void options(ActivityScenario<EditorActivity> scenario,File file,String action) {
        DialogTestUi.awaitActivityFocus(scenario);
        scenario.onActivity(a -> call(a,"showFileOptionsDialog",new Class[]{File.class},file));
        onView(withText(action)).inRoot(isDialog()).perform(click());
    }
    private static void blockedPrompt() {
        onView(withText("请先关闭文件或切出目录")).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withText("确定")).inRoot(isDialog()).perform(click());
    }
    private static void rename(String name) {
        onView(isAssignableFrom(TextInputEditText.class)).inRoot(isDialog()).perform(replaceText(name),closeSoftKeyboard());
        onView(withText("确定")).inRoot(isDialog()).perform(click());
    }
}
