package com.vkyra.code.update;

import com.vkyra.code.BuildConfig;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Lifecycle-owned release lookup. An empty configured repository makes no network request. */
public class VersionChecker implements AutoCloseable {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followSslRedirects(false).build();
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final String currentVersion;
    private final String abi;
    private final Executor callbacks;
    private final AtomicLong generation = new AtomicLong();
    private volatile Call active;
    private volatile VersionCheckCallback callback;
    private volatile boolean closed;

    public VersionChecker(Context context) {
        this(context.getApplicationContext(), HTTP, configuredEndpoint(),
                currentVersion(context), deviceAbi(), command -> new Handler(Looper.getMainLooper()).post(command));
    }

    VersionChecker(Context context, OkHttpClient client, HttpUrl endpoint, String currentVersion,
                   String abi, Executor callbacks) {
        this.context = context;
        this.client = client;
        this.endpoint = endpoint;
        this.currentVersion = currentVersion;
        this.abi = abi;
        this.callbacks = callbacks;
    }

    private static HttpUrl configuredEndpoint() {
        try { return UpdateReleaseSelector.repositoryUrl(BuildConfig.UPDATE_REPOSITORY); }
        catch (IllegalArgumentException e) { return null; }
    }

    public static boolean isConfigured() {
        try { return UpdateReleaseSelector.repositoryUrl(BuildConfig.UPDATE_REPOSITORY) != null; }
        catch (IllegalArgumentException e) { return false; }
    }

    public synchronized void checkVersion(VersionCheckCallback callback) {
        if (closed) return;
        cancel();
        this.callback = callback;
        long token = generation.get();
        if (endpoint == null) { deliver(token, false, null, null); return; }
        try { UpdateReleaseSelector.version(currentVersion); }
        catch (RuntimeException e) { deliver(token, false, null, "当前应用版本号无效"); return; }
        Call call = client.newCall(new Request.Builder().url(endpoint)
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Vkyra-Android").build());
        active = call;
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                deliver(token, false, null, "无法连接更新服务器，请稍后重试");
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response owned = response) {
                    if (!owned.isSuccessful()) throw new IOException("HTTP " + owned.code());
                    ResponseBody body = owned.body();
                    if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) throw new IOException("响应过大或为空");
                    // Bound unknown/chunked responses as well as declared content lengths.
                    okio.BufferedSource source = body.source();
                    source.request(MAX_RESPONSE_BYTES + 1L);
                    if (source.getBuffer().size() > MAX_RESPONSE_BYTES) throw new IOException("响应过大");
                    ReleaseInfo info = UpdateReleaseSelector.select(source.readUtf8(), abi);
                    boolean newer = info != null && UpdateReleaseSelector.version(info.version)
                            .compareToIgnoreBuildMetadata(UpdateReleaseSelector.version(currentVersion)) > 0;
                    deliver(token, newer, info, null);
                } catch (IOException | RuntimeException error) {
                    deliver(token, false, null, "检查更新失败，请检查网络或发布配置");
                }
            }
        });
    }

    private void deliver(long token, boolean newer, ReleaseInfo info, String error) {
        callbacks.execute(() -> {
            synchronized (VersionChecker.this) {
                if (closed || token != generation.get()) return;
                VersionCheckCallback target = callback;
                callback = null;
                active = null;
                if (target == null) return;
                if (error == null) target.onVersionCheckComplete(newer, info, currentVersion);
                else target.onVersionCheckError(error);
            }
        });
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        Call call = active;
        active = null;
        callback = null;
        if (call != null) call.cancel();
    }
    @Override public synchronized void close() { closed = true; cancel(); }

    public String getCachedLatestVersion() {
        if (!isConfigured() || context == null) return null;
        return context.getSharedPreferences("version_check", Context.MODE_PRIVATE).getString("latest_version", null);
    }

    private static String currentVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return ""; }
    }
    private static String deviceAbi() {
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) return abi;
        return "";
    }

    public interface VersionCheckCallback {
        void onVersionCheckComplete(boolean isNewVersion, ReleaseInfo releaseInfo, String currentVersion);
        void onVersionCheckError(String error);
    }
    public static class ReleaseInfo {
        public String version;
        public String body;
        public String downloadUrl;
        public ReleaseInfo(String version, String body, String downloadUrl) {
            this.version = version; this.body = body; this.downloadUrl = downloadUrl;
        }
    }
}
