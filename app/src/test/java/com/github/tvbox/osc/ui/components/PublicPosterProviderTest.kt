package com.github.tvbox.osc.ui.components

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPosterProviderTest {

    @Test
    fun tvMazeImageIsReturnedWithoutCallingLaterSource() {
        var calls = 0
        val provider = PublicPosterProvider { url ->
            calls++
            assertTrue(url.contains("tvmaze", ignoreCase = true))
            """{"image":{"original":"https://static.tvmaze.com/uploads/images/original_untouched/1/123.jpg"}}"""
        }

        val poster = provider.findPoster(VodImages.PosterLookup("douban", "1", "The Office"))

        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/1/123.jpg", poster)
        assertEquals(1, calls)
    }

    @Test
    fun sourceExceptionIsIsolatedAndNextSourceCanSucceed() {
        var calls = 0
        val provider = PublicPosterProvider { url ->
            calls++
            if (url.contains("tvmaze", ignoreCase = true)) throw IOException("offline")
            """{"d":[{"i":{"imageUrl":"https://m.media-amazon.com/images/M/MV5.jpg"}}]}"""
        }

        val poster = provider.findPoster(VodImages.PosterLookup("douban", "1", "The Office"))

        assertEquals("https://m.media-amazon.com/images/M/MV5.jpg", poster)
        assertEquals(2, calls)
    }

    @Test
    fun playbackAndDetailUrlsAreRejected() {
        assertNull(PublicPosterProvider.parsePosterUrl("""{"image":{"original":"https://cdn.example.com/show.m3u8"}}""", "tvmaze"))
        assertNull(PublicPosterProvider.parsePosterUrl("""{"image":{"original":"https://cdn.example.com/detail/123.jpg"}}""", "tvmaze"))
        assertTrue(PublicPosterProvider.isExplicitImageUrl("https://cdn.example.com/poster.webp"))
        assertTrue(!PublicPosterProvider.isExplicitImageUrl("https://cdn.example.com/video.mp4"))
    }
}
