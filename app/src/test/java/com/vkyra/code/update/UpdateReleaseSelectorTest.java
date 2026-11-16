package com.vkyra.code.update;

import static org.junit.Assert.*;
import org.junit.Test;

public class UpdateReleaseSelectorTest {
    @Test public void repositoryMustBeExplicitAndCannotEscapePath() {
        assertNull(UpdateReleaseSelector.repositoryUrl(""));
        assertEquals("https://api.github.com/repos/Vkyra/code/releases?per_page=100", UpdateReleaseSelector.repositoryUrl("Vkyra/code").toString());
        for (String invalid : new String[]{"https://github.com/Vkyra/code", "../code", "owner/repo/extra", " owner/repo", "owner/repo?x=1"}) {
            try { UpdateReleaseSelector.repositoryUrl(invalid); fail(invalid); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void semanticVersionsPreservePrereleaseAndIgnoreBuildPrecedence() {
        assertTrue(UpdateReleaseSelector.version("v1.10.0").compareToIgnoreBuildMetadata(UpdateReleaseSelector.version("1.9.9")) > 0);
        assertTrue(UpdateReleaseSelector.version("1.2.3-rc.2").compareToIgnoreBuildMetadata(UpdateReleaseSelector.version("1.2.3")) < 0);
        assertEquals(0, UpdateReleaseSelector.version("v.1.2").compareToIgnoreBuildMetadata(UpdateReleaseSelector.version("1.2.0+build.9")));
        assertTrue(UpdateReleaseSelector.version("2147483648.0.0").compareToIgnoreBuildMetadata(UpdateReleaseSelector.version("2.0.0")) > 0);
        try { UpdateReleaseSelector.version("garbage"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void armAndX86NeverFallBackToEachOtherOrChecksum() throws Exception {
        String x86 = release("2.0.0", false, false, "Vkyra_x86_64.apk");
        assertNull(UpdateReleaseSelector.select("[" + x86 + "]", "arm64-v8a"));
        assertNotNull(UpdateReleaseSelector.select("[" + x86 + "]", "x86_64"));
        assertNull(UpdateReleaseSelector.select("[" + release("2.0.0", false, false, "Vkyra_arm64.apk.sha256") + "]", "arm64-v8a"));
        assertNull(UpdateReleaseSelector.select("[" + release("2.0.0", false, false, "Vkyra.apk") + "]", "arm64-v8a"));
        assertNotNull(UpdateReleaseSelector.select("[" + release("2.0.0", false, false, "Vkyra_universal.apk") + "]", "arm64-v8a"));
        assertNotNull(UpdateReleaseSelector.select("[" + release("2.0.0", false, false, "Vkyra_aarch64.apk") + "]", "arm64-v8a"));
        assertNull(UpdateReleaseSelector.select("[" + x86 + "]", "armeabi-v7a"));
    }
    @Test public void selectsHighestCompatibleStableRatherThanFirstRelease() throws Exception {
        String json = "[" + release("v3.0.0", true, false, "app_aarch64.apk") + ","
                + release("v2.1.0-rc.1", false, false, "app_aarch64.apk") + ","
                + release("v2.0.0", false, true, "app_aarch64.apk") + ","
                + release("v1.8.0", false, false, "app_aarch64.apk") + ","
                + release("v1.9.0", false, false, "app_aarch64.apk") + "]";
        assertEquals("1.9.0", UpdateReleaseSelector.select(json, "arm64-v8a").version);
    }
    @Test public void datedVersionsRetainDisplayZerosAndCompareAcrossDays() throws Exception {
        assertTrue(UpdateReleaseSelector.version("0.1.01117").compareToIgnoreBuildMetadata(
                UpdateReleaseSelector.version("0.1.01116")) > 0);
        String json = "[" + release("v0.1.01116", false, false, "app_aarch64.apk") + ","
                + release("v0.1.01117", false, false, "app_aarch64.apk") + "]";
        assertEquals("0.1.01117", UpdateReleaseSelector.select(json, "arm64-v8a").version);
    }
    @Test public void rejectsNonHttpsAndCredentialUrls() {
        assertFalse(UpdateReleaseSelector.isSecureUrl("http://example.com/app.apk"));
        assertFalse(UpdateReleaseSelector.isSecureUrl("https://user:token@example.com/app.apk"));
        assertTrue(UpdateReleaseSelector.isSecureUrl("https://github.com/Vkyra/code/releases/download/v2/app.apk"));
    }
    static String release(String version, boolean draft, boolean prerelease, String filename) {
        return "{\"tag_name\":\""+version+"\",\"draft\":"+draft+",\"prerelease\":"+prerelease
                +",\"body\":\"changes\",\"assets\":[{\"name\":\""+filename+"\",\"browser_download_url\":\"https://example.com/"+filename+"\"}]}";
    }
}
