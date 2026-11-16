package com.vkyra.code.git;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class GitAuthScopeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void canonicalOriginKeepsSchemeAndPortBoundaries() {
        GitRemoteIdentity github = GitRemoteIdentity.fromSavedScope("GitHub.com");
        assertEquals(github, GitRemoteIdentity.fromRemote("https://GITHUB.com:443/a.git"));
        assertNotEquals(github, GitRemoteIdentity.fromRemote("https://github.com.attacker.invalid/a.git"));
        assertNotEquals(github, GitRemoteIdentity.fromRemote("https://notgithub.com/a.git"));
        assertNotEquals(github, GitRemoteIdentity.fromRemote("https://github.com:8443/a.git"));
        assertNotEquals(github, GitRemoteIdentity.fromRemote("http://github.com/a.git"));
        assertNotEquals(github, GitRemoteIdentity.fromRemote("git@github.com:a.git"));
        assertNull(GitRemoteIdentity.fromRemote("/storage/repo"));
        assertNull(GitRemoteIdentity.fromSavedScope(""));
    }

    @Test public void acceptsCanonicalIpv6AndInternationalizedHosts() {
        assertEquals(GitRemoteIdentity.fromRemote("https://[::1]:443/a"),
                GitRemoteIdentity.fromRemote("https://[::1]/b"));
        assertEquals(GitRemoteIdentity.fromRemote("https://bücher.example/a"),
                GitRemoteIdentity.fromRemote("https://xn--bcher-kva.example/b"));
    }

    @Test public void providerResolvesActualOriginAndExplicitAccount() throws Exception {
        try (OriginCredentialsProvider provider = new OriginCredentialsProvider()
                .add("github.com", "test-account", "TEST_ONLY_DUMMY_TOKEN")) {
            CredentialItem.Username username = new CredentialItem.Username();
            CredentialItem.Password password = new CredentialItem.Password();
            assertFalse(provider.get(new URIish("https://github.com.attacker.invalid/a"), username, password));
            assertNull(password.getValue());
            assertFalse(provider.get(new URIish("https://other-account@github.com/a"), username, password));
            assertFalse(provider.get(new URIish("http://github.com/a"), username, password));
            assertTrue(provider.get(new URIish("https://test-account@github.com/a"), username, password));
            assertEquals("test-account", username.getValue());
            assertArrayEquals("TEST_ONLY_DUMMY_TOKEN".toCharArray(), password.getValue());
        }
    }

    @Test public void closedProviderCannotDeliverCredentials() throws Exception {
        OriginCredentialsProvider provider = new OriginCredentialsProvider()
                .add("github.com", "test-account", "TEST_ONLY_DUMMY_TOKEN");
        provider.close();
        assertFalse(provider.get(new URIish("https://github.com/a"), new CredentialItem.Password()));
    }

    @Test public void crossPortRedirectNeverReceivesAuthorizationOrEvenARequest() throws Exception {
        AtomicInteger authenticatedRequests = new AtomicInteger();
        try (MockWebServer destination = new MockWebServer(); MockWebServer source = new MockWebServer()) {
            destination.enqueue(new MockResponse().setResponseCode(404));
            destination.start();
            source.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getHeader("Authorization") == null) {
                        return new MockResponse().setResponseCode(401)
                                .setHeader("WWW-Authenticate", "Basic realm=\"test-only\"");
                    }
                    authenticatedRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(302)
                            .setHeader("Location", destination.url(request.getPath()));
                }
            });
            source.start();
            String remote = source.url("/repository.git").toString();
            try (OriginCredentialsProvider provider = new OriginCredentialsProvider()
                    .add(remote, "test-account", "TEST_ONLY_DUMMY_TOKEN")) {
                try {
                    Git.cloneRepository().setURI(remote).setDirectory(temporary.newFolder("redirect-clone"))
                            .setCredentialsProvider(provider).setTimeout(5)
                            .setTransportConfigCallback(OriginHttpConnectionFactory::configure).call().close();
                    fail("Cross-origin redirect must fail");
                } catch (org.eclipse.jgit.api.errors.TransportException expected) {
                    assertEquals(1, authenticatedRequests.get());
                    assertEquals(0, destination.getRequestCount());
                }
            }
        }
    }
}
