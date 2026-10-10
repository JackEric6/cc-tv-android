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

    @Test
    fun prefetchedCandidatesKeepPrimaryAndBackupUrlsTogether() {
        val cache = YspPrefetchCache()
        val candidates = listOf(
            "https://live.example/main.m3u8",
            "https://backup.example/live.m3u8",
        )
        cache.putCandidates("ysp://cctv1", candidates)

        assertEquals(candidates, cache.takeCandidates("ysp://cctv1"))
        assertNull(cache.takeCandidates("ysp://cctv1"))
    }

    @Test
    fun fallbackQueueReturnsEachAlternativeOnceInOrder() {
        val queue = YspFallbackQueue()
        queue.set("ysp://cctv1", listOf("https://backup-a.example/live.m3u8", "https://backup-b.example/live.m3u8"))

        assertEquals("https://backup-a.example/live.m3u8", queue.next("ysp://cctv1"))
        assertEquals("https://backup-b.example/live.m3u8", queue.next("ysp://cctv1"))
        assertNull(queue.next("ysp://cctv1"))
    }

    @Test
    fun resolverKeepsOnlyDistinctOfficialCdnCandidates() {
        val body = """
            {"iretcode":0,
             "playurl":"https://live.cctv.cn/main.m3u8",
             "backurl_list":[
               {"url":"https://backup.cctv.com/backup.m3u8"},
               {"url":"https://live.cctv.cn/main.m3u8"},
               {"url":"https://untrusted.example/ad.m3u8"}
             ]}
        """.trimIndent()

        assertEquals(
            listOf("https://live.cctv.cn/main.m3u8", "https://backup.cctv.com/backup.m3u8"),
            YspLiveResolver.parsePlayUrls(body),
        )
    }
}
