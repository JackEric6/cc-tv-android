package com.github.tvbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubanPosterProviderTest {

    private val provider = DoubanPosterProvider()

    @Test
    fun parsesSubjectSuggestTitleYearImageAndId() {
        val result = provider.parseSuggestions(
            """
            [{"title":"流浪地球 2","year":"2023","img":"//img9.doubanio.com/view/photo/s_ratio_poster/public/p123.jpg","id":"35267208"}]
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals("流浪地球 2", result[0].title)
        assertEquals(2023, result[0].year)
        assertEquals("35267208", result[0].id)
        assertEquals(
            "https://img9.doubanio.com/view/photo/s_ratio_poster/public/p123.jpg",
            result[0].imageUrl,
        )
    }

    @Test
    fun exactTitleWinsAndMatchingYearAddsScore() {
        val candidates = provider.parseSuggestions(
            """
            [
              {"title":"沙丘","year":"2021","img":"https://img1.doubanio.com/a.jpg","id":"1"},
              {"title":"沙丘：第二部","year":"2024","img":"https://img1.doubanio.com/b.jpg","id":"2"},
              {"title":"沙丘","year":"2024","img":"https://img1.doubanio.com/c.jpg","id":"3"}
            ]
            """.trimIndent(),
        )

        val selected = provider.selectCandidate(candidates, "沙丘", 2024, null)

        assertEquals("3", selected?.id)
    }

    @Test
    fun knownDoubanIdHasPriorityOverTitle() {
        val candidates = provider.parseSuggestions(
            """[{"title":"另一部电影","year":"2020","img":"http://img2.doubanio.com/a.jpg","id":"12345"}]""",
        )

        val selected = provider.selectCandidate(candidates, "片名", 2020, "12345")

        assertEquals("12345", selected?.id)
    }

    @Test
    fun cleansProtocolEscapingAndRejectsNonDoubanHost() {
        assertEquals(
            "https://img3.doubanio.com/view/photo/p.jpg?a=1&b=2",
            provider.cleanImageUrl("http:\\/\\/img3.doubanio.com/view/photo/p.jpg?a=1&amp;b=2"),
        )
        assertEquals(
            "https://img.douban.com/view/photo/p.jpg",
            provider.cleanImageUrl("img.douban.com/view/photo/p.jpg"),
        )
        assertNull(provider.cleanImageUrl("https://example.com/poster.jpg"))
        assertTrue(provider.cleanImageUrl("//img1.doubanio.com/poster.jpg")!!.startsWith("https://"))
    }
}
