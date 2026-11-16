package com.vkyra.code.update;

import com.github.zafarkhaja.semver.Version;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/** GitHub's release schema, SemVer precedence and explicit supported APK variants. */
final class UpdateReleaseSelector {
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern ARM = Pattern.compile("(?:^|[^a-z0-9])(?:aarch64|arm64(?:-v8a)?)(?:$|[^a-z0-9])");
    private static final Pattern X86 = Pattern.compile("(?:^|[^a-z0-9])(?:x86[_-]64|amd64)(?:$|[^a-z0-9])");
    private static final Pattern UNIVERSAL = Pattern.compile("(?:^|[^a-z0-9])universal(?:$|[^a-z0-9])");

    static HttpUrl repositoryUrl(String repository) {
        if (repository == null || repository.trim().isEmpty()) return null;
        if (!REPOSITORY.matcher(repository).matches() || repository.contains(".."))
            throw new IllegalArgumentException("更新仓库必须为 owner/repository");
        String[] parts = repository.split("/");
        return new HttpUrl.Builder().scheme("https").host("api.github.com")
                .addPathSegment("repos").addPathSegment(parts[0]).addPathSegment(parts[1])
                .addPathSegment("releases").addQueryParameter("per_page", "100").build();
    }

    static Version version(String value) {
        if (value == null) throw new IllegalArgumentException("缺少版本号");
        String text = value.trim();
        if (text.startsWith("v.")) text = text.substring(2);
        else if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        return Version.tryParse(text, false).orElseThrow(() -> new IllegalArgumentException("无效版本号"));
    }

    static VersionChecker.ReleaseInfo select(String json, String abi) throws IOException {
        Release[] releases;
        try { releases = new Gson().fromJson(json, Release[].class); }
        catch (RuntimeException e) { throw new IOException("更新服务器返回了无效数据", e); }
        if (releases == null) throw new IOException("更新服务器返回了空数据");
        Version best = null;
        VersionChecker.ReleaseInfo result = null;
        for (Release release : releases) {
            if (release == null || release.draft || release.prerelease) continue;
            Version candidate;
            try { candidate = version(release.tag_name); }
            catch (RuntimeException e) { continue; }
            if (candidate.isPreRelease() || (best != null && candidate.compareToIgnoreBuildMetadata(best) <= 0)) continue;
            Asset selected = selectAsset(release.assets, abi);
            if (selected == null) continue;
            best = candidate;
            result = new VersionChecker.ReleaseInfo(candidate.toString(), release.body == null ? "" : release.body,
                    selected.browser_download_url);
        }
        return result;
    }

    static Asset selectAsset(Asset[] assets, String abi) {
        if (assets == null || !("arm64-v8a".equals(abi) || "x86_64".equals(abi))) return null;
        Asset universal = null;
        for (Asset asset : assets) {
            if (asset == null || asset.name == null || !isSecureUrl(asset.browser_download_url)) continue;
            String name = asset.name.toLowerCase(Locale.ROOT);
            if (!name.endsWith(".apk")) continue;
            boolean arm = ARM.matcher(name).find(), x86 = X86.matcher(name).find();
            if (arm && x86) continue;
            if (("arm64-v8a".equals(abi) && arm) || ("x86_64".equals(abi) && x86)) return asset;
            if (!arm && !x86 && UNIVERSAL.matcher(name).find()) universal = asset;
        }
        return universal;
    }

    static boolean isSecureUrl(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value);
        return url != null && url.isHttps() && url.username().isEmpty() && url.password().isEmpty();
    }

    static final class Release {
        @SerializedName("tag_name") String tag_name;
        @SerializedName("body") String body;
        @SerializedName("draft") boolean draft;
        @SerializedName("prerelease") boolean prerelease;
        @SerializedName("assets") Asset[] assets;
    }
    static final class Asset {
        @SerializedName("name") String name;
        @SerializedName("browser_download_url") String browser_download_url;
    }
}
