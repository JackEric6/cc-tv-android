package com.github.tvbox.osc.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.util.HawkConfig;

import org.junit.Test;

/** 直链快路径只识别媒体地址，不误把播放器页送进播放器。 */
public class PlaybackControllerFastPathTest {
    @Test
    public void acceptsDirectMediaUrlsWithQueryAndFragment() {
        assertTrue(PlaybackController.isDirectMediaUrl("https://cdn.example/video.mp4?token=1"));
        assertTrue(PlaybackController.isDirectMediaUrl("http://cdn.example/live/index.m3u8#live"));
        assertTrue(PlaybackController.isDirectMediaUrl("https://cdn.example/live/stream.MPD"));
        assertTrue(PlaybackController.isDirectMediaUrl("data:application/dash+xml;base64,abc"));
    }

    @Test
    public void rejectsPlayerPagesAndUnsupportedUrls() {
        assertFalse(PlaybackController.isDirectMediaUrl("https://site.example/player?id=42"));
        assertFalse(PlaybackController.isDirectMediaUrl("https://site.example/vod/detail/42.html"));
        assertFalse(PlaybackController.isDirectMediaUrl("tvbox-xg:https://cdn.example/video.mp4"));
        assertFalse(PlaybackController.isDirectMediaUrl(""));
    }

    @Test
    public void externalPlayerRequiresExplicitSelectionAndNoLocalOverride() {
        assertFalse(PlaybackController.shouldUseExternalPlayer(10, false, false, false));
        assertTrue(PlaybackController.shouldUseExternalPlayer(10, false, false, true));
        assertFalse(PlaybackController.shouldUseExternalPlayer(10, true, false, true));
        assertFalse(PlaybackController.shouldUseExternalPlayer(10, false, true, true));
        assertFalse(PlaybackController.shouldUseExternalPlayer(2, false, false, true));
    }

    @Test
    public void m3u8PurificationGetsEnoughTimeForPlaylistFetches() {
        String playlist = "https://cdn.example/video/index.m3u8";
        assertTrue(HawkConfig.M3U8_PURIFY_DEFAULT);
        assertTrue(PlaybackController.shouldPurifyM3u8(playlist, true, false));
        assertFalse(PlaybackController.shouldPurifyM3u8(playlist, true, false, "zanpian"));
        assertTrue(PlaybackController.shouldPurifyM3u8(playlist, true, false, "ffzy"));
        assertTrue(PlaybackController.shouldPurifyM3u8(
                "https://cdn.example/live/index.m3u8?url=token-value", true, false));
        assertEquals(
                PlaybackController.M3U8_PURIFY_SWITCH_LINE_TIMEOUT_MS,
                PlaybackController.switchLineTimeoutForUrl(playlist, true, false));
        assertEquals(20_000L, PlaybackController.switchLineTimeoutForUrl(playlist, false, false));
        assertEquals(20_000L, PlaybackController.switchLineTimeoutForUrl(playlist, true, true));
        assertEquals(20_000L, PlaybackController.switchLineTimeoutForUrl(
                "http://127.0.0.1:9978/proxyM3u8?k=abc", true, false));
        assertEquals(20_000L, PlaybackController.switchLineTimeoutForUrl(
                "https://site.example/play?url=https%3A%2F%2Fcdn.example%2Findex.m3u8", true, false));
        assertEquals(20_000L, PlaybackController.switchLineTimeoutForUrl(
                playlist, true, false, "zanpian"));
    }
}
