package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.ui.page.HomeViewModel.PartitionState
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.MovieSort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun shouldShowHomeContent_keepsLoadingUntilBootAndCategoriesAreReady() {
        assertFalse(
            HomeViewModel.shouldShowHomeContent(
                bootReady = false,
                sortsLoaded = true,
                recState = PartitionState.Ready,
                partitionStates = emptyList(),
            ),
        )
        assertFalse(
            HomeViewModel.shouldShowHomeContent(
                bootReady = true,
                sortsLoaded = false,
                recState = PartitionState.Ready,
                partitionStates = emptyList(),
            ),
        )
        assertFalse(
            HomeViewModel.shouldShowHomeContent(
                bootReady = true,
                sortsLoaded = true,
                recState = PartitionState.Loading,
                partitionStates = listOf(PartitionState.Loading, PartitionState.Loading),
            ),
        )
    }

    @Test
    fun shouldShowHomeContent_allowsEmptyRecommendation() {
        assertTrue(
            HomeViewModel.shouldShowHomeContent(
                bootReady = true,
                sortsLoaded = true,
                recState = PartitionState.Empty,
                partitionStates = listOf(PartitionState.Loading),
            ),
        )
    }

    @Test
    fun shouldShowHomeContent_allowsFirstFinishedPartition() {
        assertTrue(
            HomeViewModel.shouldShowHomeContent(
                bootReady = true,
                sortsLoaded = true,
                recState = PartitionState.Loading,
                partitionStates = listOf(PartitionState.Ready, PartitionState.Loading),
            ),
        )
        assertTrue(
            HomeViewModel.shouldShowHomeContent(
                bootReady = true,
                sortsLoaded = true,
                recState = PartitionState.Loading,
                partitionStates = listOf(PartitionState.Error, PartitionState.Loading),
            ),
        )
    }

    @Test
    fun defaultSortId_alwaysUsesFirstCategory() {
        val sorts = listOf(
            MovieSort.SortData("first", "国产剧"),
            MovieSort.SortData("second", "喜剧片"),
        )

        assertEquals("first", HomeViewModel.defaultSortId(sorts))
        assertNull(HomeViewModel.defaultSortId(emptyList()))
    }

    @Test
    fun sourceKeyMustBeExplicitlyMatchedToRejectUnmarkedStaleResults() {
        assertFalse(HomeViewModel.isCurrentSourceResult("source-b", null))
        assertFalse(HomeViewModel.isCurrentSourceResult("source-b", "source-a"))
        assertTrue(HomeViewModel.isCurrentSourceResult("source-b", "source-b"))
    }

    @Test
    fun partitionRequestKeySeparatesReloadsButDeduplicatesQueuedCategoryLoads() {
        assertEquals("7:drama", HomeViewModel.partitionRequestKey(7, "drama"))
        assertEquals(
            HomeViewModel.partitionRequestKey(7, "drama"),
            HomeViewModel.partitionRequestKey(7, "drama"),
        )
        assertFalse(
            HomeViewModel.partitionRequestKey(7, "drama") ==
                HomeViewModel.partitionRequestKey(8, "drama"),
        )
    }

    @Test
    fun homeSourcesForSelectionUsesSelectedFeedbackSourceAndKeepsGuaziFallback() {
        val guazi = source("guazi")
        val xigua = source("xgzy")
        val jianpian = source("jianpian")
        val sources = listOf(guazi, xigua, jianpian)

        assertEquals(listOf(guazi, xigua), HomeViewModel.homeSourcesForSelection(sources, "guazi"))
        assertEquals(listOf(xigua), HomeViewModel.homeSourcesForSelection(sources, "xgzy"))
        assertEquals(listOf(jianpian), HomeViewModel.homeSourcesForSelection(sources, "jianpian"))
        assertEquals(listOf(guazi, xigua), HomeViewModel.homeSourcesForSelection(sources, "unknown"))
    }

    private fun source(key: String) = SourceBean().apply {
        setKey(key)
        setChangeable(1)
    }
}
