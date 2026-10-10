package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OfflineHlsTransformerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void onlyAtomicallyCommittedSegmentIsReusable() throws Exception {
        File completed = folder.newFile("segment-0");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(completed)) {
            output.write(new byte[]{1, 2, 3});
        }
        File partial = folder.newFile("segment-1.part");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(partial)) {
            output.write(new byte[]{1, 2, 3});
        }
        assertTrue(OfflineHlsTransformer.isReusableSegment(completed));
        assertFalse(OfflineHlsTransformer.isReusableSegment(partial));
    }

    @Test
    public void finiteVodUsesTransformerAndQueryUrlIsAccepted() {
        String url = "https://cdn.example/vod/index.m3u8?token=abc";
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:6,\nseg-1.ts?token=abc\n#EXT-X-ENDLIST\n";
        assertTrue(OfflineHlsTransformer.isHlsVodCandidate(url));
        assertTrue(OfflineHlsTransformer.isFiniteVodPlaylist(playlist));
        assertTrue(OfflineHlsTransformer.shouldUseTransformer(url, playlist));
    }

    @Test
    public void liveAndEventPlaylistsAreRejected() {
        String url = "https://cdn.example/live/index.m3u8";
        String live = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n";
        String event = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n"
                + "#EXTINF:6,\nseg.ts\n#EXT-X-ENDLIST\n";
        assertFalse(OfflineHlsTransformer.shouldUseTransformer(url, live));
        assertFalse(OfflineHlsTransformer.shouldUseTransformer(url, event));
    }

    @Test
    public void encryptedVodIsAttemptedButLowLatencyIsRejected() {
        String url = "https://cdn.example/vod/index.m3u8";
        String encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"
                + "#EXTINF:6,\nseg.ts\n#EXT-X-ENDLIST\n";
        String lowLatency = "#EXTM3U\n#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"part.ts\"\n#EXT-X-ENDLIST\n";
        assertTrue(OfflineHlsTransformer.shouldUseTransformer(url, encrypted));
        assertFalse(OfflineHlsTransformer.shouldUseTransformer(url, lowLatency));
    }

    @Test
    public void plainTsVodCanUseDirectSegmentDownload() {
        String url = "https://cdn.example/vod/index.m3u8";
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:6,\nsegment-1.ts\n"
                + "#EXTINF:6,\n/parts/segment-2.ts?token=abc\n"
                + "#EXT-X-ENDLIST\n";
        assertTrue(OfflineHlsTransformer.isDirectTsPlaylist(url, playlist));
        assertEquals(2, OfflineHlsTransformer.parseDirectTsSegments(url, playlist).size());
        assertEquals("https://cdn.example/vod/segment-1.ts",
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(0).url);
        assertEquals("https://cdn.example/parts/segment-2.ts?token=abc",
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(1).url);
        assertEquals(6_000L,
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(0).durationMs);
    }

    @Test
    public void directTsParserKeepsFractionalSegmentDurations() {
        String playlist = "#EXTM3U\n#EXTINF:5.25,\npart.ts\n#EXT-X-ENDLIST\n";
        assertEquals(5_250L,
                OfflineHlsTransformer.parseDirectTsSegments(
                        "https://cdn.example/vod/index.m3u8", playlist).get(0).durationMs);
    }

    @Test
    public void encryptedOrFmp4VodFallsBackToTransformer() {
        String url = "https://cdn.example/vod/index.m3u8";
        String encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"
                + "#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n";
        String fmp4 = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:6,\nsegment.m4s\n#EXT-X-ENDLIST\n";
        assertFalse(OfflineHlsTransformer.isDirectTsPlaylist(url, encrypted));
        assertFalse(OfflineHlsTransformer.isDirectTsPlaylist(url, fmp4));
    }

    @Test
    public void byteRangeVodFallsBackToTransformer() {
        String url = "https://cdn.example/vod/index.m3u8";
        String byteRange = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-BYTERANGE:1200@0\n#EXTINF:6,\nmedia.ts\n"
                + "#EXT-X-BYTERANGE:1300\n#EXTINF:6,\nmedia.ts\n#EXT-X-ENDLIST\n";
        assertFalse(OfflineHlsTransformer.isDirectTsPlaylist(url, byteRange));
        assertTrue(OfflineHlsTransformer.shouldUseTransformer(url, byteRange));
    }

    @Test
    public void masterPlaylistSelectsHighestBandwidthVariant() throws Exception {
        String masterUrl = "https://cdn.example/vod/master.m3u8";
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=700000\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhi/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1500000\nmid/index.m3u8\n";

        // 通过行为入口验证变体选择，不依赖网络或 Android 播放器。
        Method selector = OfflineHlsTransformer.class.getDeclaredMethod(
                "selectVariant", String.class, String.class);
        selector.setAccessible(true);
        String selected = (String) selector.invoke(null, masterUrl, master);
        assertEquals("https://cdn.example/vod/hi/index.m3u8", selected);
    }

    @Test
    public void directTsParserPreservesPlaylistOrder() {
        String url = "https://cdn.example/vod/index.m3u8";
        String playlist = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n"
                + "#EXTINF:6,\r\npart-03.ts\r\n"
                + "#EXT-X-DISCONTINUITY\r\n"
                + "#EXTINF:6,\r\npart-01.ts?token=x\r\n"
                + "#EXTINF:6,\r\npart-02.ts\r\n#EXT-X-ENDLIST\r\n";
        assertEquals("https://cdn.example/vod/part-03.ts",
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(0).url);
        assertEquals("https://cdn.example/vod/part-01.ts?token=x",
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(1).url);
        assertEquals("https://cdn.example/vod/part-02.ts",
                OfflineHlsTransformer.parseDirectTsSegments(url, playlist).get(2).url);
    }
}
