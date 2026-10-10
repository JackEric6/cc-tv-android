package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodImages
import com.github.tvbox.osc.util.SearchHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConcurrencyTest {

    @Test
    fun invalidThreadCountStillLeavesSearchRunnable() {
        assertEquals(1, SearchViewModel.normalizeSearchPermits(0))
        assertEquals(1, SearchViewModel.normalizeSearchPermits(-4))
    }

    @Test
    fun configuredThreadCountIsBounded() {
        assertEquals(16, SearchViewModel.normalizeSearchPermits(16))
        assertEquals(64, SearchViewModel.normalizeSearchPermits(128))
    }

    @Test
    fun searchSourcesUseRequestedPriorityOrder() {
        val keys = listOf(
            "other", "360zy", "zanpian", "ikanbot", "fan_nomi", "fan_cheese", "fan_moli", "cz4k", "fhw88",
            "lzi", "dyttzy", "bfzy", "ffzy", "xgzy", "fan_jianpian", "jianpian", "guazi",
        )
        val ordered = keys.sortedWith(compareBy { SearchViewModel.sourcePriority(it, it) })
        assertEquals(
            listOf(
                "guazi", "fan_jianpian", "jianpian", "xgzy", "ffzy", "bfzy", "dyttzy",
                "lzi", "fhw88", "cz4k", "fan_moli", "fan_cheese", "fan_nomi", "ikanbot", "zanpian", "other", "360zy",
            ),
            ordered,
        )
        assertEquals(0, SearchViewModel.sourcePriority("guazi", "瓜子"))
        assertEquals(1, SearchViewModel.sourcePriority("fan_jianpian", "荐片┃原生"))
        assertEquals(2, SearchViewModel.sourcePriority("xgzy", "西瓜"))
        assertEquals(4, SearchViewModel.sourcePriority("cms_baofeng", "暴风┃本地"))
        assertEquals(100, SearchViewModel.sourcePriority("360zy", "360"))
        assertEquals(10, SearchViewModel.sourcePriority("fan_cheese", "奶酪"))
        assertEquals(11, SearchViewModel.sourcePriority("fan_nomi", "🍓糯米┃秒播"))
        assertEquals(12, SearchViewModel.sourcePriority("ikanbot", "爱看"))
        assertEquals(13, SearchViewModel.sourcePriority("zanpian", "赞片"))
        assertEquals(100, SearchViewModel.sourcePriority("unlisted", "其他影视源"))
    }

    @Test
    fun duplicateBaofengAliasesShareOneDisplaySourceAndKeepTheirVideos() {
        fun video(name: String, key: String) = Movie.Video().apply {
            this.name = name
            sourceKey = key
        }

        val displayed = SearchViewModel.sortResultsForDisplay(
            listOf(
                SearchViewModel.SourceResult(
                    "bfzy", "暴风", SearchViewModel.ResultState.Done,
                    listOf(video("抓特务", "bfzy"), video("共同影片", "bfzy")),
                ),
                SearchViewModel.SourceResult(
                    "cms_baofeng", "暴风", SearchViewModel.ResultState.Done,
                    listOf(video("共同影片", "cms_baofeng"), video("另一部", "cms_baofeng")),
                ),
            ),
        )

        assertEquals(1, displayed.size)
        assertEquals("暴风", displayed.single().sourceName)
        assertEquals(setOf("bfzy", "cms_baofeng"), displayed.single().sourceKeys)
        assertEquals(listOf("抓特务", "共同影片", "另一部"), displayed.single().videos.map { it.name })
        assertEquals(
            listOf("bfzy", "bfzy", "cms_baofeng"),
            displayed.single().videos.map { it.sourceKey },
        )
    }

    @Test
    fun searchResultDisplayUsesPriorityAndPreservesConfiguredOrderWithinGroups() {
        fun result(key: String, arrivedAt: Int) = SearchViewModel.SourceResult(
            sourceKey = key,
            sourceName = key,
            state = SearchViewModel.ResultState.Done,
            videos = emptyList(),
            arrivedAt = arrivedAt,
        )

        val displayed = SearchViewModel.sortResultsForDisplay(
            listOf(
                result("other-b", 1),
                result("xgzy", 6),
                result("guazi-alt", 2).copy(sourceName = "瓜子二"),
                result("other-a", 3),
                result("guazi", 5).copy(sourceName = "瓜子一"),
                result("jianpian", 4),
            ),
        )
        assertEquals(
            listOf("瓜子二", "瓜子一", "jianpian", "xgzy", "other-b", "other-a"),
            displayed.map { it.sourceName },
        )
    }

    @Test
    fun searchResultsRequirePosterOrVerifiedSharedPoster() {
        VodImages.setExternalPosterProvider(null, false)
        val video = Movie.Video().apply {
            name = "沉默的荣耀"
            pic = ""
        }
        val filtered = SearchViewModel.filterSearchVideos(
            listOf(video),
            exact = false,
            title = "沉默的荣耀",
        )

        assertTrue(filtered.isEmpty())

        video.pic = "https://source.example/poster.jpg"
        assertEquals(1, SearchViewModel.filterSearchVideos(listOf(video), false, "沉默的荣耀").size)

        try {
            video.pic = ""
            VodImages.rememberVerifiedPoster("沉默的荣耀", "https://other.example/poster.jpg")
            assertEquals(1, SearchViewModel.filterSearchVideos(listOf(video), false, "沉默的荣耀").size)
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun webCmsSearchResultWithoutPosterIsNotDroppedByUiFilter() {
        VodImages.setExternalPosterProvider(null, false)
        val video = Movie.Video().apply {
            name = "沉默的荣耀"
            pic = ""
        }

        assertEquals(
            1,
            SearchViewModel.filterSearchVideos(
                listOf(video),
                exact = false,
                title = "沉默的荣耀",
                allowPosterless = SearchHelper.allowsPosterlessSearchResult("qiumu"),
            ).size,
        )
        assertTrue(
            SearchViewModel.filterSearchVideos(
                listOf(video),
                exact = false,
                title = "沉默的荣耀",
                allowPosterless = SearchHelper.allowsPosterlessSearchResult("xgzy"),
            ).isEmpty(),
        )
    }

    @Test
    fun unverifiedSharedPosterCandidateDoesNotPassSearchFilter() {
        val title = "候选海报未验证-${System.nanoTime()}"
        VodImages.setExternalPosterProvider(null, false)
        try {
            VodImages.rememberPosterCandidate(title, "https://source.example/unverified.jpg")
            val video = Movie.Video().apply {
                name = title
                pic = ""
            }

            assertTrue(
                SearchViewModel.filterSearchVideos(listOf(video), false, title).isEmpty(),
            )
        } finally {
            VodImages.setExternalPosterProvider(null, false)
        }
    }

    @Test
    fun posterFilterDoesNotChangeExactMatchFiltering() {
        val exact = Movie.Video().apply {
            name = "庆余年(2019)"
            pic = "https://source.example/exact.jpg"
        }
        val other = Movie.Video().apply {
            name = "庆余年 第二季"
            pic = "https://source.example/other.jpg"
        }
        assertEquals(
            listOf("庆余年(2019)"),
            SearchViewModel.filterSearchVideos(listOf(other, exact), true, "庆余年").map { it.name },
        )
    }
}
