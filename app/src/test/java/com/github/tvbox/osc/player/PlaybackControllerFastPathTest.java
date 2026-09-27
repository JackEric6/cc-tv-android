package com.github.tvbox.osc.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
