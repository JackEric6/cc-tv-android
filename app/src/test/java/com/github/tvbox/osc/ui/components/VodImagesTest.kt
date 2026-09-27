package com.github.tvbox.osc.ui.components

import java.lang.reflect.InvocationTargetException
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodImagesTest {

    @Test
    fun legalAtInUrlIsNotTreatedAsMetadata() {
        val raw = "https://user@example.com/poster.jpg"

        assertNull(VodImages.parseVodPicUrl(raw))
    }

    @Test
    fun metadataMarkersAreParsedWithoutTruncatingUrlUserInfo() {
        val raw = "https://user@example.com/poster.jpg@Headers=%7B%22Origin%22%3A%22https%3A%2F%2Fexample.com%22%7D@Cookie=session%3Dok@User-Agent=Poster%2F1.0@Referer=https%3A%2F%2Fexample.com%2Fdetail"

        val parsed = VodImages.parseVodPicUrl(raw)

        assertNotNull(parsed)
        assertEquals("https://user@example.com/poster.jpg", parsed!!.first)
        assertEquals("https://example.com", parsed.second["Origin"])
        assertEquals("session=ok", parsed.second["Cookie"])
        assertEquals("Poster/1.0", parsed.second["User-Agent"])
        assertEquals("https://example.com/detail", parsed.second["Referer"])
    }

    @Test
    fun pictureClientUsesDirectSystemNetworkingAndBoundedRetry() {
        val client = invokePicClient()

        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(Dns.SYSTEM, client.dns)
        assertTrue(!client.retryOnConnectionFailure)
        assertEquals(64, client.dispatcher.maxRequests)
        assertEquals(16, client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun directFailureUsesFallbackOnceAndReturnsFallbackResult() {
        var primaryAttempts = 0
        var fallbackAttempts = 0

        val result = VodImages.executeWithPicFallback(
            primary = {
                primaryAttempts++
                throw IOException("direct connection failed")
            },
            fallback = {
                fallbackAttempts++
                "fallback-ok"
            },
        )

        assertEquals("fallback-ok", result)
        assertEquals(1, primaryAttempts)
        assertEquals(1, fallbackAttempts)
    }

    @Test
    fun selectedHttpStatusesUseFallbackButNotFoundDoesNot() {
        assertTrue(VodImages.shouldFallbackPicResponse(403))
        assertTrue(VodImages.shouldFallbackPicResponse(408))
        assertTrue(VodImages.shouldFallbackPicResponse(429))
        assertTrue(VodImages.shouldFallbackPicResponse(500))
        assertTrue(VodImages.shouldFallbackPicResponse(599))
        assertFalse(VodImages.shouldFallbackPicResponse(200))
        assertFalse(VodImages.shouldFallbackPicResponse(404))
    }

    @Test
    fun disabledExternalPosterProviderDoesNotScheduleLookup() {
        val calls = AtomicInteger()
        VodImages.setExternalPosterProvider(
            VodImages.ExternalPosterProvider {
                calls.incrementAndGet()
                "https://poster.example/a.jpg"
            },
            false,
        )
        try {
            assertEquals(
                "",
                VodImages.resolvePosterOrSchedule("", "source", "1", "片名"),
            )
            assertEquals(0, calls.get())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun providerChainStopsAtDoubanAndFallsBackInOrder() {
        val calls = mutableListOf<String>()
        val chain = VodImages.composeExternalPosterProviders(
            VodImages.ExternalPosterProvider {
                calls += "douban"
                null
            },
            VodImages.ExternalPosterProvider {
                calls += "public"
                "https://poster.example/fallback.jpg"
            },
        )

        assertEquals(
            "https://poster.example/fallback.jpg",
            chain.findPoster(VodImages.PosterLookup("source", "1", "片名")),
        )
        assertEquals(listOf("douban", "public"), calls)
    }

    @Test
    fun providerChainDoesNotCallFallbackAfterPrimaryHit() {
        val calls = mutableListOf<String>()
        val chain = VodImages.composeExternalPosterProviders(
            VodImages.ExternalPosterProvider {
                calls += "douban"
                "https://poster.example/douban.jpg"
            },
            VodImages.ExternalPosterProvider {
                calls += "public"
                "https://poster.example/fallback.jpg"
            },
        )

        assertEquals(
            "https://poster.example/douban.jpg",
            chain.findPoster(VodImages.PosterLookup("source", "1", "片名")),
        )
        assertEquals(listOf("douban"), calls)
    }

    @Test
    fun defaultProviderRegistrationEnablesAsyncFallback() {
        try {
            VodImages.registerDefaultExternalPosterProviders()
            assertTrue(VodImages.isExternalPosterProviderEnabled())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun existingPosterNeverCallsExternalProvider() {
        val calls = AtomicInteger()
        VodImages.setExternalPosterProvider(
            VodImages.ExternalPosterProvider {
                calls.incrementAndGet()
                "https://poster.example/a.jpg"
            },
            true,
        )
        try {
            assertEquals(
                "https://source.example/existing.jpg",
                VodImages.resolvePosterOrSchedule(
                    "https://source.example/existing.jpg", "source", "1", "片名",
                ),
            )
            Thread.sleep(100)
            assertEquals(0, calls.get())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun sameTitleSharesVerifiedPosterAcrossSourcesWithoutProviderRequest() {
        VodImages.setExternalPosterProvider(null, false)
        try {
            VodImages.rememberVerifiedPoster("沉默的荣耀", "https://poster.example/silent.jpg")
            assertEquals(
                "https://poster.example/silent.jpg",
                VodImages.resolvePosterOrSchedule("", "other-source", "different-id", "沉默的荣耀"),
            )
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun sameTitleSharesSourceCandidateBeforeImageDecodeWithoutProviderRequest() {
        val calls = AtomicInteger()
        VodImages.setExternalPosterProvider(
            VodImages.ExternalPosterProvider {
                calls.incrementAndGet()
                "https://poster.example/provider.jpg"
            },
            true,
        )
        try {
            VodImages.rememberPosterCandidate("沉默的荣耀", "https://source.example/silent.jpg")
            assertEquals(
                "https://source.example/silent.jpg",
                VodImages.resolvePosterOrSchedule("", "other-source", "different-id", "沉默的荣耀"),
            )
            assertEquals(0, calls.get())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun failedSourceCandidateIsRemovedSoNextCandidateCanBeUsed() {
        VodImages.setExternalPosterProvider(null, false)
        try {
            VodImages.rememberPosterCandidate("片名", "https://source.example/broken.jpg")
            VodImages.rememberPosterCandidate("片名", "https://source.example/working.jpg")
            VodImages.forgetPosterCandidate("片名", "https://source.example/broken.jpg")

            assertEquals(
                "https://source.example/working.jpg",
                VodImages.resolvePosterOrSchedule("", "other-source", "1", "片名"),
            )
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun metadataDecoratedPosterCanBeSharedUsingBaseUrl() {
        VodImages.setExternalPosterProvider(null, false)
        try {
            val decorated = "https://source.example/poster.jpg@Referer=https%3A%2F%2Fsource.example%2F"
            VodImages.rememberVerifiedPoster("片名", decorated)

            assertEquals(
                "https://source.example/poster.jpg",
                VodImages.sharedPosterForTitle("片名"),
            )
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun posterTitleNormalizationIgnoresPunctuationAndSpacing() {
        assertEquals(
            VodImages.normalizePosterTitle(" 沉默的荣耀（2025） "),
            VodImages.normalizePosterTitle("沉默的荣耀 2025"),
        )
    }

    @Test
    fun emptyPosterLookupIsAsyncAndCachesSuccessfulResult() {
        val started = CountDownLatch(1)
        val calls = AtomicInteger()
        VodImages.setExternalPosterProvider(
            VodImages.ExternalPosterProvider {
                calls.incrementAndGet()
                started.countDown()
                "https://poster.example/a.jpg"
            },
            true,
        )
        try {
            val startNanos = System.nanoTime()
            assertEquals("", VodImages.resolvePosterOrSchedule("", "source", "1", "片名"))
            assertTrue(System.nanoTime() - startNanos < TimeUnit.MILLISECONDS.toNanos(250))
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals("https://poster.example/a.jpg", awaitPoster("source", "1", "片名"))
            assertEquals(1, calls.get())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun failedLookupIsRememberedWithoutRepeatedProviderCalls() {
        val started = CountDownLatch(1)
        val calls = AtomicInteger()
        VodImages.setExternalPosterProvider(
            VodImages.ExternalPosterProvider {
                calls.incrementAndGet()
                started.countDown()
                null
            },
            true,
        )
        try {
            VodImages.resolvePosterOrSchedule("", "source", "missing", "不存在")
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals("", VodImages.resolvePosterOrSchedule("", "source", "missing", "不存在"))
            assertEquals(1, calls.get())
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    private fun awaitPoster(source: String, id: String, title: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val poster = VodImages.resolvePosterOrSchedule("", source, id, title)
            if (poster.isNotEmpty()) return poster
            Thread.sleep(20)
        }
        return ""
    }

    private fun invokePicClient(): OkHttpClient {
        try {
            val method = VodImages::class.java.getDeclaredMethod("picClient")
            method.isAccessible = true
            return method.invoke(VodImages) as OkHttpClient
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
