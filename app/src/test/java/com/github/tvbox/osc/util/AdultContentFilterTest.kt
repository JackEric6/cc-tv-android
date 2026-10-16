package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultContentFilterTest {

    @After
    fun restoreDefaultState() {
        AdultContentFilter.resetForTests()
    }

    @Test
    fun filterIsEnabledByDefaultAndMatchesTitleOrCategory() {
        val direct = video("午夜伦理片", "剧情")
        val category = video("普通标题", "限制级")
        assertTrue(AdultContentFilter.isEnabled())
        assertTrue(AdultContentFilter.isDirectAdult(direct))
        assertTrue(AdultContentFilter.isDirectAdult(category))
        assertEquals(emptyList<Movie.Video>(), AdultContentFilter.filter(listOf(direct, category)))
    }

    @Test
    fun sessionDisableKeepsItemsAndDoesNotPersistInUtilityState() {
        AdultContentFilter.disableForSession()
        assertFalse(AdultContentFilter.isEnabled())
        val item = video("午夜伦理片", "剧情")
        assertEquals(listOf(item), AdultContentFilter.filter(listOf(item)))
    }

    @Test
    fun normalizedAdultTitleHidesSameWorkAcrossSources() {
        val marked = video("示例片 4K", "伦理")
        val sameWork = video("示例片（高清）", "剧情")
        val other = video("普通作品", "剧情")
        val keys = AdultContentFilter.collectAdultTitleKeys(listOf(marked))
        val result = AdultContentFilter.filterByAdultTitles(
            listOf(marked, sameWork, other),
            keys,
            "示例片",
        )
        assertEquals(listOf(other), result)
    }

    @Test
    fun eighteenPlusAllowsWhitespaceBeforePlus() {
        assertTrue(AdultContentFilter.isDirectAdult(video("作品 18 +", "")))
        assertTrue(AdultContentFilter.isDirectAdult(video("作品18+", "")))
    }

    private fun video(name: String, type: String): Movie.Video = Movie.Video().apply {
        this.name = name
        this.type = type
    }
}
