package com.github.tvbox.osc.util.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YspPrefetchCacheTest {
    @Test
    fun prefetchedUrlCanOnlyBeConsumedOnce() {
        val cache = YspPrefetchCache()
        cache.put("ysp://cctv1", "https://live.example/channel.m3u8")

        assertEquals("https://live.example/channel.m3u8", cache.take("ysp://cctv1"))
        assertNull(cache.take("ysp://cctv1"))
    }

    @Test
    fun expiredPrefetchedUrlIsDiscarded() {
        var now = 1_000L
        val cache = YspPrefetchCache(ttlMillis = 15_000, nowMillis = { now })
        cache.put("ysp://cctv1", "https://live.example/channel.m3u8")
        now += 15_001

        assertNull(cache.take("ysp://cctv1"))
    }
}
