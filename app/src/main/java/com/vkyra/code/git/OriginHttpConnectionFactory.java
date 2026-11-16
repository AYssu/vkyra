package com.vkyra.code.git;

import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;

/** Keep redirects within the effective transport origin, including its scheme and port. */
public final class OriginHttpConnectionFactory implements HttpConnectionFactory2 {
    private final GitRemoteIdentity origin;
    private final HttpConnectionFactory delegate;

    OriginHttpConnectionFactory(GitRemoteIdentity origin, HttpConnectionFactory delegate) {
        this.origin = origin;
        this.delegate = delegate;
    }

    public static void configure(Transport transport) {
        if (transport instanceof TransportHttp) {
            TransportHttp http = (TransportHttp) transport;
            http.setHttpConnectionFactory(new OriginHttpConnectionFactory(
                    GitRemoteIdentity.fromUri(transport.getURI()), http.getHttpConnectionFactory()));
        }
    }

    private void check(URL url) throws IOException {
        GitRemoteIdentity requested = GitRemoteIdentity.fromUri(new URIish(url));
        if (origin == null || !origin.equals(requested)) {
            throw new IOException("Git 跨站重定向被阻止，请直接使用目标仓库地址");
        }
    }

    @Override public HttpConnection create(URL url) throws IOException {
        check(url);
        return delegate.create(url);
    }

    @Override public HttpConnection create(URL url, Proxy proxy) throws IOException {
        check(url);
        return delegate.create(url, proxy);
    }

    @Override public GitSession newSession() {
        if (delegate instanceof HttpConnectionFactory2) {
            return ((HttpConnectionFactory2) delegate).newSession();
        }
        return new GitSession() {
            @Override public HttpConnection configure(HttpConnection connection, boolean sslVerify) {
                return connection;
            }
            @Override public void close() {}
        };
    }
}
