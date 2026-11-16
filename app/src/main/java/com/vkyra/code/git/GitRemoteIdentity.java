package com.vkyra.code.git;

import org.eclipse.jgit.transport.URIish;

import java.net.IDN;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** A canonical network origin. Repository paths never broaden credential scope. */
public final class GitRemoteIdentity {
    private final String scheme;
    private final String host;
    private final int port;

    private GitRemoteIdentity(String scheme, String host, int port) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
    }

    /** Parse a remote URL using the same parser as the Git transport. */
    public static GitRemoteIdentity fromRemote(String remote) {
        if (remote == null || remote.trim().isEmpty()) return null;
        try {
            return fromUri(new URIish(remote.trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    static GitRemoteIdentity fromUri(URIish uri) {
        if (uri == null || uri.getHost() == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null) scheme = "ssh"; // JGit's scp-like git@host:path syntax.
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"ssh".equals(scheme)) {
            return null;
        }
        try {
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            host = host.indexOf(':') >= 0 ? host.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return null;
            // A terminal DNS dot denotes the same host; do not remove subdomain labels.
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return null;
            int port = uri.getPort();
            if (port == -1) port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : 22;
            if (port < 1 || port > 65535) return null;
            return new GitRemoteIdentity(scheme, host, port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Legacy bare host entries were used for HTTPS and must never authorize HTTP or SSH. */
    public static GitRemoteIdentity fromSavedScope(String savedScope) {
        if (savedScope == null || savedScope.trim().isEmpty()) return null;
        String value = savedScope.trim();
        if (!value.contains("://")) {
            if (value.indexOf('/') >= 0 || value.indexOf('@') >= 0 || value.indexOf('?') >= 0
                    || value.indexOf('#') >= 0) return null;
            value = "https://" + value;
        }
        return fromRemote(value);
    }

    public boolean isHttp() {
        return "https".equals(scheme) || "http".equals(scheme);
    }

    public String getHost() {
        return host;
    }

    public String toOrigin() {
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        int defaultPort = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : 22;
        return scheme + "://" + authority + (port == defaultPort ? "" : ":" + port);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GitRemoteIdentity)) return false;
        GitRemoteIdentity identity = (GitRemoteIdentity) other;
        return port == identity.port && scheme.equals(identity.scheme) && host.equals(identity.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, host, port);
    }
}
