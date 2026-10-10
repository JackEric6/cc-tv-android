package com.github.tvbox.osc.ui.page

import android.content.ContextWrapper
import com.github.tvbox.osc.util.OfflineVideoCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPageTest {
    @Test
    fun repeatedBindUsesExistingApplicationContextWithoutRebindingListener() {
        val context = ContextWrapper(null)
        val otherContext = ContextWrapper(null)

        assertTrue(shouldRefreshBoundContext(context, context))
        assertFalse(shouldRefreshBoundContext(context, otherContext))
    }

    @Test
    fun downloadTabRefreshesOnlyWhenItBecomesCurrent() {
        assertTrue(downloadTabIsCurrent(2, 2))
        assertFalse(downloadTabIsCurrent(0, 2))
    }

    @Test
    fun pausedTasksBelongToActiveTab() {
        assertEquals(0, downloadTabFor(OfflineVideoCache.TaskStatus.QUEUED))
        assertEquals(0, downloadTabFor(OfflineVideoCache.TaskStatus.DOWNLOADING))
        assertEquals(0, downloadTabFor(OfflineVideoCache.TaskStatus.PAUSED))
        assertEquals(1, downloadTabFor(OfflineVideoCache.TaskStatus.COMPLETED))
        assertEquals(2, downloadTabFor(OfflineVideoCache.TaskStatus.FAILED))
        assertEquals(2, downloadTabFor(OfflineVideoCache.TaskStatus.CANCELLED))
    }

    @Test
    fun retryStatesDoNotSeedDisplayFromPreviousProgress() {
        assertEquals(-1, initialDisplayedPercent(OfflineVideoCache.TaskStatus.QUEUED, 99))
        assertEquals(-1, initialDisplayedPercent(OfflineVideoCache.TaskStatus.PAUSED, 99))
        assertEquals(-1, initialDisplayedPercent(OfflineVideoCache.TaskStatus.FAILED, 99))
        assertEquals(-1, initialDisplayedPercent(OfflineVideoCache.TaskStatus.CANCELLED, 99))
        assertEquals(-1, initialDisplayedPercent(OfflineVideoCache.TaskStatus.DOWNLOADING, -1))
        assertEquals(3, initialDisplayedPercent(OfflineVideoCache.TaskStatus.DOWNLOADING, 3))
        assertEquals(100, initialDisplayedPercent(OfflineVideoCache.TaskStatus.COMPLETED, 99))
    }

    @Test
    fun speedDisplayUsesRawFreshValueAndExpiresAfterAStall() {
        assertEquals(
            4096L,
            displayedSpeedFor(OfflineVideoCache.TaskStatus.DOWNLOADING, 4096L, 1_000L, 2_000L),
        )
        assertEquals(
            0L,
            displayedSpeedFor(OfflineVideoCache.TaskStatus.DOWNLOADING, 4096L, 1_000L, 2_501L),
        )
        assertEquals(
            0L,
            displayedSpeedFor(OfflineVideoCache.TaskStatus.PAUSED, 4096L, 1_000L, 1_500L),
        )
    }

    @Test
    fun newerPolledSnapshotWinsAcrossStatusTransitions() {
        assertTrue(shouldPreferLiveSnapshot(taskUpdatedAt = 100L, liveUpdatedAt = 101L))
        assertFalse(shouldPreferLiveSnapshot(taskUpdatedAt = 101L, liveUpdatedAt = 100L))
        assertTrue(shouldPreferLiveSnapshot(taskUpdatedAt = 100L, liveUpdatedAt = 100L))
    }

    @Test
    fun progressDisplayAcceptsRecalibratedOrUnknownCoreValue() {
        assertEquals(
            25,
            displayedPercentFor(OfflineVideoCache.TaskStatus.DOWNLOADING, 25, false),
        )
        assertEquals(
            -1,
            displayedPercentFor(OfflineVideoCache.TaskStatus.DOWNLOADING, -1, false),
        )
        assertEquals(-1, displayedPercentFor(OfflineVideoCache.TaskStatus.DOWNLOADING, 99, true))
        assertEquals(
            100,
            displayedPercentFor(OfflineVideoCache.TaskStatus.COMPLETED, 25, false),
        )
    }

    @Test
    fun estimatedTotalUsesDisplayedProgressWhenCoreTotalIsUnknown() {
        assertEquals(4_000L, estimateTotalBytes(1_000L, -1L, 25))
        assertEquals(4_000L, estimateTotalBytes(1_000L, 4_000L, 25))
        assertEquals(-1L, estimateTotalBytes(1_000L, -1L, -1))
        assertEquals(-1L, estimateTotalBytes(1_000L, -1L, 100))
    }

    @Test
    fun overallProgressIsByteWeightedAndMarksUnknownTasks() {
        val totals = aggregateDownloadTotals(
            listOf(
                DownloadProgressInput(OfflineVideoCache.TaskStatus.DOWNLOADING, 790L, 1_000L, 79),
                DownloadProgressInput(OfflineVideoCache.TaskStatus.DOWNLOADING, 50L, -1L, 50),
                DownloadProgressInput(OfflineVideoCache.TaskStatus.QUEUED, 0L, -1L, -1),
                DownloadProgressInput(OfflineVideoCache.TaskStatus.COMPLETED, 100L, 100L, 100),
            ),
        )

        assertEquals(3, totals.unfinishedTasks)
        assertEquals(1, totals.unknownTasks)
        assertEquals(840L, totals.downloadedBytes)
        assertEquals(1_100L, totals.totalBytes)
        assertEquals(76, totals.progressPercent)
    }

    @Test
    fun overallProgressRemainsUnknownWhenNoTaskHasAKnownOrEstimatedSize() {
        val totals = aggregateDownloadTotals(
            listOf(
                DownloadProgressInput(OfflineVideoCache.TaskStatus.DOWNLOADING, 1_000L, -1L, -1),
                DownloadProgressInput(OfflineVideoCache.TaskStatus.QUEUED, 0L, -1L, -1),
            ),
        )

        assertEquals(2, totals.unknownTasks)
        assertNull(totals.progressPercent)
    }

    @Test
    fun overallProgressPrefersDurationBasedSizeEstimate() {
        val totals = aggregateDownloadTotals(
            listOf(
                DownloadProgressInput(
                    OfflineVideoCache.TaskStatus.DOWNLOADING,
                    2_000L,
                    -1L,
                    10,
                    estimatedTotalBytes = 20_000L,
                ),
            ),
        )

        assertEquals(20_000L, totals.totalBytes)
        assertEquals(10, totals.progressPercent)
    }

    @Test
    fun remainingTimeUsesRemainingBytesAndCurrentSpeed() {
        assertEquals(30L, estimateRemainingSeconds(1_000L, 4_000L, 100L))
        assertEquals(3_601L, estimateRemainingSeconds(0L, 3_601L, 1L))
        assertEquals(0L, estimateRemainingSeconds(4_000L, 4_000L, 100L))
        assertNull(estimateRemainingSeconds(1_000L, -1L, 100L))
        assertNull(estimateRemainingSeconds(1_000L, 4_000L, 0L))
        assertEquals("2分5秒", formatRemainingTime(125L))
        assertEquals("1小时1分", formatRemainingTime(3_661L))
        assertEquals(3_600L, roundRemainingSecondsForDisplay(3_590L))
        assertEquals(3_300L, roundRemainingSecondsForDisplay(3_280L))
    }

    @Test
    fun overallSpeedAggregatesByteDeltasAcrossRollingSamples() {
        assertEquals(
            3_000L,
            overallDownloadSpeed(
                listOf(
                    OverallDownloadSpeedSample(1_000_000_000L, 1_000L, 500_000_000L),
                    OverallDownloadSpeedSample(1_500_000_000L, 2_000L, 500_000_000L),
                ),
            ),
        )
        assertEquals(0L, overallDownloadSpeed(emptyList()))
    }

    @Test
    fun initialSpeedSampleIsIgnoredAndImplausibleRatesAreRejected() {
        assertTrue(shouldResetSpeedBaseline(baselineReady = false, restarted = false, deltaBytes = 500_000_000L))
        assertTrue(shouldResetSpeedBaseline(baselineReady = true, restarted = true, deltaBytes = 1024L))
        assertFalse(shouldResetSpeedBaseline(baselineReady = true, restarted = false, deltaBytes = 1024L))
        assertEquals(2L * 1024L * 1024L, downloadSpeedSample(1L * 1024L * 1024L, 500_000_000L))
        assertEquals(0L, downloadSpeedSample(200L * 1024L * 1024L, 500_000_000L))
        assertEquals(0L, displayedSpeedFor(
            OfflineVideoCache.TaskStatus.DOWNLOADING,
            300L * 1024L * 1024L,
            1_000L,
            1_500L,
        ))
    }
}
