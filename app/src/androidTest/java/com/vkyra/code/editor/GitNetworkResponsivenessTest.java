package com.vkyra.code.editor;

import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.vkyra.code.R;
import com.vkyra.code.git.GitManager;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static com.vkyra.code.editor.EditorInteractionTest.*;

/** The actual Editor Git button must not wait for the repository's slow HTTP command. */
@RunWith(AndroidJUnit4.class)
public class GitNetworkResponsivenessTest {
    @Test public void actualGitButtonLeavesMainLooperResponsiveWhilePullWaitsForHttp() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        try (Fixture fixture = new Fixture(); ServerSocket server = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(15_000);
            Thread http = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(15_000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = input.readLine();
                    assertNotNull(request);
                    assertTrue("real Git smart HTTP request required", request.contains("info/refs"));
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                    entered.countDown();
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Throwable error) { serverError.set(error); }
            }, "vkyra-owned-slow-git-http");
            http.setDaemon(true);
            try (Git git = Git.init().setDirectory(fixture.project).call()) {
                StoredConfig config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Vkyra Responsiveness Test");
                config.setString("user", null, "email", "test@vkyra.invalid");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor("Vkyra", "test@vkyra.invalid").call();
                config.setString("remote", "origin", "url", "http://127.0.0.1:" + server.getLocalPort() + "/repository.git");
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
                String branch = git.getRepository().getBranch();
                config.setString("branch", branch, "remote", "origin");
                config.setString("branch", branch, "merge", git.getRepository().getFullBranch());
                config.save();
            }
            AtomicReference<GitManager> manager = new AtomicReference<>();
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                awaitReady(scenario);
                await(scenario, a -> a.findViewById(R.id.action_git) != null
                        && a.findViewById(R.id.action_git).isShown(), "Git button missing");
                scenario.onActivity(a -> manager.set((GitManager) get(a, "gitManager")));
                http.start();
                Future<?> operation = manager.get().pull(new GitManager.GitCallback<String>() {
                    @Override public void onSuccess(String value) { }
                    @Override public void onError(String error) { }
                });
                assertTrue("pull never reached the real HTTP server", entered.await(10, TimeUnit.SECONDS));
                assertFalse(operation.isDone());
                CountDownLatch clickReturned = new CountDownLatch(1), heartbeat = new CountDownLatch(1);
                AtomicReference<Throwable> clickError = new AtomicReference<>();
                AtomicReference<android.view.View> button = new AtomicReference<>();
                scenario.onActivity(a -> button.set(a.findViewById(R.id.action_git)));
                Handler main = new Handler(Looper.getMainLooper());
                try {
                    main.post(() -> {
                        try { assertTrue(button.get().performClick()); }
                        catch (Throwable error) { clickError.set(error); }
                        finally { clickReturned.countDown(); }
                    });
                    main.post(heartbeat::countDown);
                    assertTrue("Git button blocked the main looper behind network IO", clickReturned.await(2, TimeUnit.SECONDS));
                    assertTrue("next main-loop event must run while HTTP is still blocked", heartbeat.await(1, TimeUnit.SECONDS));
                    assertNull(clickError.get());
                    assertEquals("network must remain blocked during responsiveness proof", 1, release.getCount());
                    assertFalse(operation.isDone());
                } finally { release.countDown(); }
                operation.get(10, TimeUnit.SECONDS);
                // The dialog opens asynchronously. Match its window explicitly rather than
                // waiting on the underlying editor window, which loses focus to the dialog.
                onView(withText("Git 操作")).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText("关闭")).inRoot(isDialog()).perform(click());
            } finally {
                release.countDown();
                if (manager.get() != null) manager.get().close();
                server.close();
                http.join(5_000);
            }
            assertFalse("owned HTTP fixture thread leaked", http.isAlive());
            assertNull("HTTP fixture failed", serverError.get());
        } finally { release.countDown(); }
    }
}
