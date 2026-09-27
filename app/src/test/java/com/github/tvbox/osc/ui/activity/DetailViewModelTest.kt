package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailViewModelTest {

    @Test
    fun detailVideoAlwaysBindsToRequestSourceWhenCmsOmitsSourceKey() {
        val video = Movie.Video().apply { sourceKey = "" }

        bindVideoSource(video, "  source-b  ")

        assertEquals("source-b", video.sourceKey)
    }

    @Test
    fun detailVideoRebindsStaleCmsSourceBeforePlayback() {
        val video = Movie.Video().apply { sourceKey = "xgzy" }

        bindVideoSource(video, "sony")

        assertEquals("sony", video.sourceKey)
    }

    @Test
    fun emptyRequestSourceDoesNotEraseReusableMetadataSource() {
        val video = Movie.Video().apply { sourceKey = "metadata-source" }

        bindVideoSource(video, "  ")

        assertEquals("metadata-source", video.sourceKey)
    }

    @Test
    fun playbackSourcePrefersActualRequestOverReusableMetadataSource() {
        assertEquals("play-source", resolvePlaybackSourceKey(" play-source ", "metadata-source"))
        assertEquals("metadata-source", resolvePlaybackSourceKey(" ", " metadata-source "))
    }

    @Test
    fun sameTitleMetadataFillsOnlyMissingFieldsAndKeepsPlaybackState() {
        val current = VodInfo().apply {
            id = "current-id"
            sourceKey = "current-source"
            pic = ""
            actor = ""
            year = 0
            playFlag = "current-line"
        }
        val currentSeries = VodInfo.VodSeries("第1集", "https://current.example/1.m3u8")
        current.seriesMap = linkedMapOf("current-line" to listOf(currentSeries))
        val donor = Movie.Video().apply {
            id = "other-id"
            sourceKey = "other-source"
            pic = "https://img.example/poster.jpg"
            actor = "演员甲"
            director = "导演乙"
            year = 2026
            urlBean = Movie.Video.UrlBean().apply {
                infoList = listOf(
                    Movie.Video.UrlBean.UrlInfo().apply {
                        flag = "other-line"
                        beanList = listOf(
                            Movie.Video.UrlBean.UrlInfo.InfoBean(
                                "第1集",
                                "https://other.example/1.m3u8",
                            ),
                        )
                    },
                )
            }
        }

        assertTrue(mergeMissingVodMetadata(current, donor))
        assertEquals("https://img.example/poster.jpg", current.pic)
        assertEquals("演员甲", current.actor)
        assertEquals("导演乙", current.director)
        assertEquals(2026, current.year)
        assertEquals("current-id", current.id)
        assertEquals("current-source", current.sourceKey)
        assertEquals("current-line", current.playFlag)
        assertEquals("https://current.example/1.m3u8", current.seriesMap?.get("current-line")?.first()?.url)
    }

    @Test
    fun normalizedTitleMatchesPunctuationAndWhitespaceDifferences() {
        assertEquals(normalizedDetailTitle("沉默的荣耀"), normalizedDetailTitle(" 沉默的·荣耀 "))
    }
}
