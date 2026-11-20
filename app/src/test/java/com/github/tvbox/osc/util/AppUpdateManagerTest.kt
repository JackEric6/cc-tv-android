package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun apiProbeUrlsUseWindowsStyleProxyPrefixAndDirectFallback() {
        val urls = AppUpdateManager.releaseApiProbeUrls()

        assertTrue(urls.size > 1)
        assertTrue(urls.first().startsWith("https://gh-proxy.org/https://api.github.com/"))
        assertEquals(
            "https://api.github.com/repos/JackEric6/cc-tv-android/releases/latest",
            urls.last(),
        )
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun downloadUrlsKeepOriginalAssetUrlAsFinalFallback() {
        val original = "https://github.com/JackEric6/cc-tv-android/releases/download/1.0.79/app.apk"
        val urls = AppUpdateManager.releaseDownloadUrls(original)

        assertTrue(urls.first().endsWith(original))
        assertEquals(original, urls.last())
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun versionsNormalizeAndCompareAcrossReleaseSuffixes() {
        assertEquals("1.0.79", AppUpdateManager.normalizeVersion("v1.0.79-release"))
        assertEquals(1, AppUpdateManager.compareVersions("1.0.79", "1.0.78"))
        assertEquals(0, AppUpdateManager.compareVersions("1.0.79-release", "v1.0.79"))
        assertEquals(-1, AppUpdateManager.compareVersions("1.0.78", "1.0.79"))
    }
}
