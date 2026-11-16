package com.vkyra.code.editor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.view.View;
import com.google.android.material.textfield.TextInputEditText;
import com.vkyra.code.R;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;

/** Real toolbar/dialog actions against an isolated local repository; no remotes or credentials. */
@RunWith(AndroidJUnit4.class)
public class GitUiInstrumentedTest {
    @Test public void statusStageCancelAndCommitButtonsChangeTheActualRepository() throws Exception {
        String changed = "// changed on disk\n" + Fixture.SOURCE;
        String added = "int helper() { return 7; }\n";
        String message = "UI local commit 中文";
        try (Fixture f = new Fixture()) {
            Files.write(new File(f.project, ".gitignore").toPath(), ".akode/\n".getBytes(StandardCharsets.UTF_8));
            ObjectId originalHead;
            try (Git git = Git.init().setDirectory(f.project).call()) {
                StoredConfig config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Vkyra UI Test");
                config.setString("user", null, "email", "ui-test@vkyra.invalid");
                config.save();
                git.add().addFilepattern(".").call();
                originalHead = git.commit().setMessage("fixture initial commit").call().getId();
            }
            Files.write(f.first.toPath(), changed.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(f.project, "added.cpp").toPath(), added.getBytes(StandardCharsets.UTF_8));
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
                awaitReady(scenario);
                await(scenario, a -> a.findViewById(R.id.action_git) != null
                        && a.findViewById(R.id.action_git).isShown(), "repository Git toolbar action missing");

                openMenu();
                onView(withText(startsWith("📊 查看状态"))).perform(click());
                awaitDisplayed(withText(allOf(containsString("已修改 (1)"), containsString("main.cpp"),
                        containsString("未跟踪 (1)"), containsString("added.cpp"))));
                onView(withText("确定")).perform(click());

                openMenu();
                onView(withText("➕ 暂存全部")).perform(click());
                awaitRepository(f.project, git -> {
                    Status status = git.status().call();
                    return status.getChanged().contains("main.cpp") && status.getAdded().contains("added.cpp")
                            && status.getModified().isEmpty() && status.getUntracked().isEmpty();
                }, "stage button did not update the real index");

                openMenu();
                onView(withText("📝 提交")).perform(click());
                onView(isAssignableFrom(TextInputEditText.class)).perform(replaceText(message), closeSoftKeyboard());
                onView(withText("取消")).perform(click());
                try (Git git = Git.open(f.project)) {
                    assertEquals("cancel must not create a commit", originalHead, git.getRepository().resolve("HEAD"));
                    assertTrue("cancel preserves staged source", git.status().call().getChanged().contains("main.cpp"));
                    assertTrue("cancel preserves staged new file", git.status().call().getAdded().contains("added.cpp"));
                }

                openMenu();
                onView(withText("📝 提交")).perform(click());
                onView(isAssignableFrom(TextInputEditText.class)).perform(replaceText(message), closeSoftKeyboard());
                onView(withText("提交")).perform(click());
                awaitRepository(f.project, git -> !originalHead.equals(git.getRepository().resolve("HEAD"))
                        && git.status().call().isClean(), "commit button did not create a clean committed tree");
                try (Git git = Git.open(f.project)) {
                    var commit = git.log().setMaxCount(1).call().iterator().next();
                    assertEquals(message, commit.getFullMessage());
                    assertEquals(originalHead, commit.getParent(0).getId());
                    assertEquals(changed, committedText(git, commit.getTree().getId(), "main.cpp"));
                    assertEquals(added, committedText(git, commit.getTree().getId(), "added.cpp"));
                    assertTrue("fixture must never acquire a remote", git.remoteList().call().isEmpty());
                }
                assertEquals(changed, read(f.first));
                assertEquals(added, read(new File(f.project, "added.cpp")));
                openMenu();
                onView(withText(startsWith("📊 查看状态"))).perform(click());
                awaitDisplayed(withText("工作区干净，没有变更"));
                onView(withText("确定")).perform(click());
            }
        }
    }

    private static void openMenu() {
        onView(withId(R.id.action_git)).perform(click());
        awaitDisplayed(withText("Git 操作"));
    }
    private static void awaitDisplayed(org.hamcrest.Matcher<View> matcher) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Throwable last = null;
        do {
            try { onView(matcher).check(matches(isDisplayed())); return; }
            catch (androidx.test.espresso.NoMatchingViewException | AssertionError pending) { last = pending; }
            android.os.SystemClock.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("asynchronous Git dialog did not become visible", last);
    }
    private interface RepositoryCondition { boolean matches(Git git) throws Exception; }
    private static void awaitRepository(File directory, RepositoryCondition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try (Git git = Git.open(directory)) { if (condition.matches(git)) return; }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail(message);
    }
    private static String committedText(Git git, ObjectId tree, String path) throws Exception {
        try (TreeWalk entry = TreeWalk.forPath(git.getRepository(), path, tree)) {
            assertNotNull("committed path missing: " + path, entry);
            return new String(git.getRepository().open(entry.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }
}
