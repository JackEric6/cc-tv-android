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
        val keys = listOf("juliang", "xgzy", "moduzy", "ffzy", "lzi", "other")
        val ordered = keys.sortedWith(compareBy { SearchViewModel.sourcePriority(it, it) })
        assertEquals(listOf("xgzy", "ffzy", "lzi", "moduzy", "juliang", "other"), ordered)
    }

    @Test
    fun searchResultDisplayUsesPriorityWithoutDependingOnArrivalOrder() {
        fun result(key: String, arrivedAt: Int) = SearchViewModel.SourceResult(
            sourceKey = key,
            sourceName = key,
            state = SearchViewModel.ResultState.Done,
            videos = emptyList(),
            arrivedAt = arrivedAt,
        )

        val displayed = SearchViewModel.sortResultsForDisplay(
            listOf(
                result("other", 1),
                result("moduzy", 4),
                result("xgzy", 3),
                result("ffzy", 2),
                result("lzi", 5),
            ),
        )
        assertEquals(
            listOf("xgzy", "ffzy", "lzi", "moduzy", "other"),
            displayed.map { it.sourceKey },
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
