package com.github.tvbox.osc.ui.page

import android.content.ContextWrapper
import com.github.tvbox.osc.util.OfflineVideoCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
