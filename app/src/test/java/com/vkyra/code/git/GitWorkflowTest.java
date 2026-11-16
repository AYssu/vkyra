package com.vkyra.code.git;

import com.vkyra.code.files.AtomicTextFileStore;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class GitWorkflowTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stageCommitDeletionAndDiscardUseOneRepositoryOwner() throws Exception {
        File root = createRepository("project");
        try (GitManager manager = new GitManager(Runnable::run)) {
            assertTrue(manager.open(root));
            File file = new File(root, "main.cpp");
            Files.write(file.toPath(), "new text".getBytes(StandardCharsets.UTF_8));
            assertTrue(manager.getStatus().modified.contains("main.cpp"));
            GitWorkflowTest.<Void>awaitSuccess(cb -> manager.discardChanges("main.cpp", cb));
            assertEquals("original", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertTrue(file.delete());
            awaitSuccess(manager::stageAll);
            assertTrue(manager.getStatus().staged.contains("main.cpp"));
            GitWorkflowTest.<String>awaitSuccess(cb -> manager.commit("delete main", cb));
            assertFalse(manager.getStatus().hasChanges());
            assertEquals("main.cpp", manager.getRelativePath(new File(root, "main.cpp")));
            assertNull(manager.getRelativePath(new File(root.getPath() + "-sibling", "main.cpp")));
        }
    }

    @Test public void callbackQueuedBeforeCloseIsSuppressedAndManagerCanReopen() throws Exception {
        File root = createRepository("callback-project");
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        AtomicInteger deliveries = new AtomicInteger();
        try (GitManager manager = new GitManager(callbacks::add)) {
            assertTrue(manager.open(root));
            manager.getStatusAsync(new GitManager.GitCallback<GitManager.GitStatus>() {
                @Override public void onSuccess(GitManager.GitStatus result) { deliveries.incrementAndGet(); }
                @Override public void onError(String error) { deliveries.incrementAndGet(); }
            }).get(5, TimeUnit.SECONDS);
            assertFalse(callbacks.isEmpty());
            manager.close();
            callbacks.remove().run();
            assertEquals(0, deliveries.get());
            assertTrue(manager.open(root));
            assertNotNull(manager.getCurrentBranch());
        }
    }

    @Test public void reopeningSameRootDoesNotInvalidatePendingResults() throws Exception {
        File root = createRepository("same-root");
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        AtomicInteger deliveries = new AtomicInteger();
        try (GitManager manager = new GitManager(callbacks::add)) {
            assertTrue(manager.open(root));
            manager.getStatusAsync(new GitManager.GitCallback<GitManager.GitStatus>() {
                @Override public void onSuccess(GitManager.GitStatus result) { deliveries.incrementAndGet(); }
                @Override public void onError(String error) { fail(error); }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(manager.open(root));
            callbacks.remove().run();
            assertEquals(1, deliveries.get());
        }
    }

    @Test public void individualCancellationSuppressesAnUndeliveredResult() throws Exception {
        File root = createRepository("cancel-result");
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        AtomicInteger deliveries = new AtomicInteger();
        try (GitManager manager = new GitManager(callbacks::add)) {
            assertTrue(manager.open(root));
            Future<?> task = manager.getStatusAsync(new GitManager.GitCallback<GitManager.GitStatus>() {
                @Override public void onSuccess(GitManager.GitStatus result) { deliveries.incrementAndGet(); }
                @Override public void onError(String error) { deliveries.incrementAndGet(); }
            });
            task.get(5, TimeUnit.SECONDS);
            task.cancel(true);
            callbacks.remove().run();
            assertEquals(0, deliveries.get());
        }
    }

    @Test public void closingDuringNetworkCommandIsNonblockingAndSuppressesCallbacks() throws Exception {
        File root = createRepository("slow-network");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        try (Git git = Git.open(root)) {
            git.getRepository().getConfig().setString("remote", "origin", "url", server.url("/repository.git").toString());
            git.getRepository().getConfig().setString("remote", "origin", "fetch",
                    "+refs/heads/*:refs/remotes/origin/*");
            git.getRepository().getConfig().setString("branch", git.getRepository().getBranch(), "remote", "origin");
            git.getRepository().getConfig().setString("branch", git.getRepository().getBranch(), "merge",
                    git.getRepository().getFullBranch());
            git.getRepository().getConfig().save();
        }
        GitManager manager = new GitManager(Runnable::run);
        try {
            assertTrue(manager.open(root));
            Future<?> command = manager.pull(new GitManager.GitCallback<String>() {
                @Override public void onSuccess(String result) { deliveries.incrementAndGet(); }
                @Override public void onError(String error) { deliveries.incrementAndGet(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            manager.close();
            assertTrue("Close must not wait for network IO", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertTrue(command.isCancelled());
            release.countDown();
            assertTrue(manager.open(root));
            assertNotNull(manager.getCurrentBranch());
            assertEquals(0, deliveries.get());
        } finally {
            release.countDown();
            manager.close();
            server.shutdown();
        }
    }

    @Test public void clonePublishesCompleteProjectAndNeverOverwritesExistingDirectory() throws Exception {
        File source = createRepository("clone-source");
        File projects = temporary.newFolder("projects");
        try (OriginCredentialsProvider credentials = new OriginCredentialsProvider()) {
            File cloned = GitCloneManager.cloneRepository(projects, source.toURI().toString(), "cloned",
                    credentials, NullProgressMonitor.INSTANCE);
            assertTrue(new File(cloned, ".git").isDirectory());
            assertEquals("original", AtomicTextFileStore.read(new File(cloned, "main.cpp")).getText());
            File existing = new File(projects, "existing");
            assertTrue(existing.mkdir());
            try {
                GitCloneManager.cloneRepository(projects, source.toURI().toString(), "existing",
                        credentials, NullProgressMonitor.INSTANCE);
                fail("An existing empty project must not be overwritten");
            } catch (java.nio.file.FileAlreadyExistsException expected) {
                assertEquals(0, existing.list().length);
            }
            assertEquals(2, projects.list().length);
        }
    }

    @Test public void failedCloneCleansOnlyItsReservedAndStagingDirectories() throws Exception {
        File projects = temporary.newFolder("failed-projects");
        File retained = new File(projects, "retained.txt");
        Files.write(retained.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        try (OriginCredentialsProvider credentials = new OriginCredentialsProvider()) {
            try {
                GitCloneManager.cloneRepository(projects, new File(projects, "missing.git").toURI().toString(),
                        "failure", credentials, NullProgressMonitor.INSTANCE);
                fail("Missing source must fail");
            } catch (org.eclipse.jgit.api.errors.GitAPIException expected) {
                assertArrayEquals(new String[]{"retained.txt"}, projects.list());
            }
        }
    }

    @Test public void cloneNameCannotEscapeProjectRoot() throws Exception {
        for (String name : new String[]{"..", ".", "../outside", "a/b", "a\\b", "bad\0name", " "}) {
            try {
                GitCloneManager.validateProjectName(name);
                fail("Invalid name accepted");
            } catch (java.io.IOException expected) {
                // Only one path component is valid.
            }
        }
        GitCloneManager.validateProjectName("My C++ project");
        assertEquals("project", GitCloneManager.extractRepoName("git@example.invalid:owner/project.git"));
    }

    @Test public void cloneCannotPersistPasswordEmbeddedInRemoteUrl() throws Exception {
        File projects = temporary.newFolder("no-url-password");
        try (OriginCredentialsProvider credentials = new OriginCredentialsProvider()) {
            try {
                GitCloneManager.cloneRepository(projects,
                        "https://test-account:TEST_ONLY_DUMMY_TOKEN@example.invalid/repo.git", "project",
                        credentials, NullProgressMonitor.INSTANCE);
                fail("URL passwords must go through encrypted credential storage");
            } catch (java.io.IOException expected) {
                assertFalse(expected.getMessage().contains("TEST_ONLY_DUMMY_TOKEN"));
                assertEquals(0, projects.list().length);
            }
        }
    }

    @Test public void cancelledCloneDoesNotPublishOrLeaveAReservedProject() throws Exception {
        File source = createRepository("cancel-source");
        File projects = temporary.newFolder("cancel-projects");
        ProgressMonitor cancelled = new ProgressMonitor() {
            @Override public void start(int totalTasks) {}
            @Override public void beginTask(String title, int totalWork) {}
            @Override public void update(int completed) {}
            @Override public void endTask() {}
            @Override public boolean isCancelled() { return true; }
        };
        try (OriginCredentialsProvider credentials = new OriginCredentialsProvider()) {
            try {
                GitCloneManager.cloneRepository(projects, source.toURI().toString(), "cancelled", credentials, cancelled);
                fail("Cancelled clone must not publish a project");
            } catch (Exception expected) {
                assertEquals(0, projects.list().length);
            }
        }
    }

    @Test public void actualPushUrlAndTrackingRemoteNeverReceiveOriginCredentials() throws Exception {
        for (boolean push : new boolean[]{true, false}) {
            File root = createRepository(push ? "pushurl-auth" : "tracking-auth");
            try (MockWebServer origin = new MockWebServer(); MockWebServer other = new MockWebServer()) {
                origin.start();
                other.enqueue(new MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Basic realm=\"test-only\""));
                other.enqueue(new MockResponse().setResponseCode(404));
                other.start();
                String originalUrl = origin.url("/repository.git").toString();
                String effectiveUrl = other.url("/repository.git").toString();
                try (Git git = Git.open(root)) {
                    org.eclipse.jgit.lib.StoredConfig config = git.getRepository().getConfig();
                    config.setString("remote", "origin", "url", originalUrl);
                    if (push) config.setString("remote", "origin", "pushurl", effectiveUrl);
                    else {
                        config.setString("remote", "upstream", "url", effectiveUrl);
                        config.setString("remote", "upstream", "fetch", "+refs/heads/*:refs/remotes/upstream/*");
                    }
                    config.setString("branch", git.getRepository().getBranch(), "remote", push ? "origin" : "upstream");
                    config.setString("branch", git.getRepository().getBranch(), "merge", git.getRepository().getFullBranch());
                    config.save();
                }
                try (GitManager manager = new GitManager(Runnable::run,
                        () -> new OriginCredentialsProvider().add(originalUrl, "test-account", "TEST_ONLY_DUMMY_TOKEN"))) {
                    assertTrue(manager.open(root));
                    AtomicInteger errors = new AtomicInteger();
                    if (push) manager.push(errorOnly(errors)).get(5, TimeUnit.SECONDS);
                    else manager.pull(errorOnly(errors)).get(5, TimeUnit.SECONDS);
                    assertEquals(1, errors.get());
                    assertEquals(0, origin.getRequestCount());
                    assertEquals(1, other.getRequestCount());
                    assertNull(other.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
                }
            }
        }
    }

    @Test public void repeatedManagersReleaseTheirWorkersAfterClose() throws Exception {
        File root = createRepository("worker-release");
        long baseline = gitWorkerCount();
        for (int i = 0; i < 10; i++) {
            try (GitManager manager = new GitManager(Runnable::run)) {
                assertTrue(manager.open(root));
                awaitSuccess(manager::getStatusAsync);
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (gitWorkerCount() > baseline && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(gitWorkerCount() <= baseline);
    }

    @Test public void pushAndPullFollowConfiguredRemoteAndReportRejectedPush() throws Exception {
        File source = createRepository("remote-source");
        File bare = new File(temporary.getRoot(), "remote.git");
        try (Git ignored = Git.cloneRepository().setURI(source.toURI().toString())
                .setBare(true).setDirectory(bare).call()) {}
        File first = new File(temporary.getRoot(), "first");
        File second = new File(temporary.getRoot(), "second");
        try (Git ignored = Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(first).call()) {}
        try (Git ignored = Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(second).call()) {}
        configureAuthor(first);
        configureAuthor(second);
        try (GitManager firstManager = new GitManager(Runnable::run);
             GitManager secondManager = new GitManager(Runnable::run)) {
            assertTrue(firstManager.open(first));
            assertTrue(secondManager.open(second));
            Files.write(new File(first, "first.txt").toPath(), "first".getBytes(StandardCharsets.UTF_8));
            awaitSuccess(firstManager::stageAll);
            GitWorkflowTest.<String>awaitSuccess(cb -> firstManager.commit("first commit", cb));
            GitWorkflowTest.<Void>awaitSuccess(firstManager::push);
            Files.write(new File(second, "second.txt").toPath(), "second".getBytes(StandardCharsets.UTF_8));
            awaitSuccess(secondManager::stageAll);
            GitWorkflowTest.<String>awaitSuccess(cb -> secondManager.commit("second divergent commit", cb));
            AtomicReference<String> pushError = new AtomicReference<>();
            secondManager.push(new GitManager.GitCallback<Void>() {
                @Override public void onSuccess(Void result) { fail("Divergent push must not report success"); }
                @Override public void onError(String error) { pushError.set(error); }
            }).get(5, TimeUnit.SECONDS);
            assertNotNull(pushError.get());
            assertTrue(pushError.get().contains("REJECTED_NONFASTFORWARD"));
            GitWorkflowTest.<String>awaitSuccess(firstManager::pull);
        }
    }

    private File createRepository(String name) throws Exception {
        File root = temporary.newFolder(name);
        try (Git git = Git.init().setDirectory(root).call()) {
            configureAuthor(git);
            Files.write(new File(root, "main.cpp").toPath(), "original".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
        }
        return root;
    }

    private static void configureAuthor(File root) throws Exception {
        try (Git git = Git.open(root)) { configureAuthor(git); }
    }

    private static void configureAuthor(Git git) throws Exception {
        git.getRepository().getConfig().setString("user", null, "name", "Vkyra Test");
        git.getRepository().getConfig().setString("user", null, "email", "test@example.invalid");
        git.getRepository().getConfig().save();
    }

    private interface Submit<T> { Future<?> run(GitManager.GitCallback<T> callback); }

    private static long gitWorkerCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("vkyra-git")).count();
    }

    private static <T> GitManager.GitCallback<T> errorOnly(AtomicInteger errors) {
        return new GitManager.GitCallback<T>() {
            @Override public void onSuccess(T result) { fail("Authentication should have been refused"); }
            @Override public void onError(String error) { errors.incrementAndGet(); }
        };
    }

    private static <T> void awaitSuccess(Submit<T> submit) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        submit.run(new GitManager.GitCallback<T>() {
            @Override public void onSuccess(T result) { calls.incrementAndGet(); }
            @Override public void onError(String message) { error.set(message); }
        }).get(5, TimeUnit.SECONDS);
        assertNull(error.get());
        assertEquals(1, calls.get());
    }
}
