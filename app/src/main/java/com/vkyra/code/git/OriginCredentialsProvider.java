package com.vkyra.code.git;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.util.ArrayList;
import java.util.List;

/** Select credentials for the URI actually challenged by JGit, including pushurl/rewrite targets. */
public final class OriginCredentialsProvider extends CredentialsProvider implements AutoCloseable {
    private static final class Entry {
        final GitRemoteIdentity origin;
        final String username;
        final UsernamePasswordCredentialsProvider delegate;

        Entry(String scope, String username, String password) {
            this.origin = GitRemoteIdentity.fromSavedScope(scope);
            this.username = username;
            this.delegate = new UsernamePasswordCredentialsProvider(username, password);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public OriginCredentialsProvider add(String scope, String username, String password) {
        if (username != null && password != null) entries.add(new Entry(scope, username, password));
        return this;
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        // Use JGit's standard item handling, but never its unscoped credential delivery.
        try (CloseableStandardProvider probe = new CloseableStandardProvider()) {
            return probe.supports(items);
        }
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        GitRemoteIdentity actual = GitRemoteIdentity.fromUri(uri);
        if (actual == null || !actual.isHttp()) return false;
        for (Entry entry : entries) {
            if (actual.equals(entry.origin)
                    && (uri.getUser() == null || uri.getUser().equals(entry.username))) {
                return entry.delegate.get(uri, items);
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (Entry entry : entries) entry.delegate.clear();
        entries.clear();
    }

    private static final class CloseableStandardProvider extends UsernamePasswordCredentialsProvider
            implements AutoCloseable {
        CloseableStandardProvider() { super("", ""); }
        @Override public void close() { clear(); }
    }
}
