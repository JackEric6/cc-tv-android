package com.github.tvbox.osc.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.util.HawkConfig;

import org.junit.Test;

public class AppConfigMigrationTest {
    @Test
    public void migratesLegacyDefaultRemoteConfigToBundledAsset() {
        String oldDefault = "https://ghproxy.net/https://raw.githubusercontent.com/JackEric6/movie/refs/heads/xgzy-config-20260922/movie2_xgzy_all";
        assertTrue(App.shouldReplaceApiUrlWithBundledDefault("", false));
        assertTrue(App.shouldReplaceApiUrlWithBundledDefault(oldDefault, false));
        assertFalse(App.shouldReplaceApiUrlWithBundledDefault(oldDefault, true));
        assertFalse(App.shouldReplaceApiUrlWithBundledDefault(HawkConfig.DEFAULT_API_URL, false));
        assertFalse(App.shouldReplaceApiUrlWithBundledDefault("https://example.com/custom.json", false));
        assertFalse(App.shouldReplaceApiUrlWithBundledDefault(
                "https://raw.githubusercontent.com/JackEric6/movie/refs/heads/custom/other.json", false));
    }
}
