package com.vkyra.code.editor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;

/** Executes the actual asynchronous save-all entry point and actual filesystem failures. */
@RunWith(AndroidJUnit4.class)
public class EditorBuildWorkspaceRegressionTest {
    @Test public void allDirtyExistingDocumentsReachDiskBeforeBuildContinuation() throws Exception {
        exercise(false);
    }

    @Test public void secondDocumentWriteFailureKeepsItsDraftAndPreventsBuildContinuation() throws Exception {
        exercise(true);
    }

    private static void exercise(boolean failSecondWrite) throws Exception {
        try (Fixture fixture = new Fixture()) {
            File second = new File(fixture.project, "second.cpp");
            String original = "int second_disk;\n";
            String firstDraft = "int main() { return 73; }\n";
            String secondDraft = "int second_dirty; // 中文\n";
            Files.write(second.toPath(), original.getBytes(StandardCharsets.UTF_8));
            CountDownLatch continuation = new CountDownLatch(1);
            AtomicBoolean completeWorkspaceObserved = new AtomicBoolean();
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                awaitReady(scenario);
                scenario.onActivity(activity -> {
                    EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                    assertEquals(1, owner.documents.size());
                    editor(activity).setText(firstDraft);
                    EditorDocument inactive = new EditorDocument(second.getAbsolutePath());
                    inactive.content = secondDraft;
                    inactive.isModified = true;
                    owner.documents.add(inactive);
                });
                File originalBackup = new File(fixture.project, "second-original.cpp");
                if (failSecondWrite) {
                    // A directory at a file destination forces a real atomic-store failure.
                    Files.move(second.toPath(), originalBackup.toPath());
                    assertTrue(second.mkdir());
                }
                scenario.onActivity(activity -> call(activity, "saveDirtyDocumentsBefore", new Class[]{Runnable.class},
                        (Runnable) () -> {
                            try {
                                completeWorkspaceObserved.set(firstDraft.equals(read(fixture.first))
                                        && secondDraft.equals(read(second))
                                        && fixture.first.equals(get(activity, "currentFile")));
                            } catch (Exception error) { throw new AssertionError(error); }
                            continuation.countDown();
                        }));
                if (!failSecondWrite) {
                    assertTrue("save-all continuation never ran", continuation.await(15, TimeUnit.SECONDS));
                    assertTrue("build observed partial/incorrect workspace", completeWorkspaceObserved.get());
                    scenario.onActivity(activity -> {
                        EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                        for (EditorDocument doc : owner.documents) assertFalse(doc.isModified);
                    });
                } else {
                    await(scenario, activity -> !((EditorSessionViewModel) get(activity, "editorSession"))
                            .documents.get(0).isModified, "first document was not actually saved");
                    CountDownLatch ioDrained = new CountDownLatch(1);
                    scenario.onActivity(activity -> ((EditorSessionViewModel) get(activity, "editorSession"))
                            .executeIo(ioDrained::countDown));
                    assertTrue(ioDrained.await(10, TimeUnit.SECONDS));
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    assertEquals("failed save must prevent build", 1, continuation.getCount());
                    assertEquals(firstDraft, read(fixture.first));
                    assertEquals(original, read(originalBackup));
                    assertTrue(second.isDirectory());
                    scenario.onActivity(activity -> {
                        EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                        assertTrue(owner.documents.get(1).isModified);
                        assertEquals(secondDraft, owner.documents.get(1).content);
                        assertEquals(firstDraft, editor(activity).getText().toString());
                        assertEquals(fixture.first, get(activity, "currentFile"));
                    });
                }
            }
        }
    }
}
