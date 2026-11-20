package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class M3u8PurifyTest {

    @Test
    public void removesCueMarkedAdSegmentsAndReturnsCountWithContent() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/seg001.ts\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/seg002.ts\n"
                + "#EXT-X-CUE-OUT:12\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/ad001.ts\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/ad002.ts\n"
                + "#EXT-X-CUE-IN\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/seg003.ts\n"
                + "#EXT-X-ENDLIST\n";

        M3u8.PurifyResult result = M3u8.purifyWithResult(
                "https://cdn.example/vod/index.m3u8", playlist);

        assertEquals(2, result.removedSegments());
        assertFalse(result.content().contains("ad001.ts"));
        assertFalse(result.content().contains("ad002.ts"));
        assertTrue(result.content().contains("seg003.ts"));
    }

    @Test
    public void removesRepeatedShortBlocksWithOutlierUniformDurationCadence() {
        String playlist = uniformCadencePlaylist(new String[]{
                "4.166667", "4.166667", "4.000000", "4.166667",
                "4.166667", "4.000000", "4.166667", "4.166667", "4.166667"
        });

        M3u8.PurifyResult result = M3u8.purifyWithResult(
                "https://cdn.example/vod/index.m3u8", playlist);

        assertEquals(10, result.removedSegments());
        assertFalse(result.content().contains("seg000a.ts"));
        assertFalse(result.content().contains("seg0019.ts"));
        assertTrue(result.content().contains("seg0000.ts"));
        assertTrue(result.content().contains("seg0023.ts"));
    }

    @Test
    public void keepsOneOffUniformDurationBlock() {
        String playlist = uniformCadencePlaylist(new String[]{
                "4.166667", "4.166667", "4.000000", "4.166667",
                "4.166667", "4.166667", "4.166667"
        });

        M3u8.PurifyResult result = M3u8.purifyWithResult(
                "https://cdn.example/vod/index.m3u8", playlist);

        assertEquals(0, result.removedSegments());
        assertTrue(result.content().contains("seg000a.ts"));
    }

    @Test
    public void removesBoundedSpsVariantGroupsWhenSurroundedByDominantEncoding() {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        Map<String, String> signatures = new HashMap<>();
        int segment = 0;
        String[] groupSignatures = {"main", "ad-variant", "ad-variant", "main", "main"};
        for (String groupSignature : groupSignatures) {
            playlist.append("#EXT-X-DISCONTINUITY\n");
            for (int i = 0; i < 2; i++) {
                String url = "https://xgzy.example/vod/seg" + String.format(java.util.Locale.ROOT, "%04d", segment++) + ".ts";
                playlist.append("#EXTINF:4.000,\n").append(url).append('\n');
                signatures.put(url, groupSignature);
            }
        }
        playlist.append("#EXT-X-ENDLIST\n");

        M3u8.PurifyResult result = M3u8.purifyWithResult(
                "https://xgzy.example/vod/index.m3u8", playlist.toString(), signatures);

        assertEquals(4, result.removedSegments());
        assertFalse(result.content().contains("seg0002.ts"));
        assertFalse(result.content().contains("seg0003.ts"));
        assertTrue(result.content().contains("seg0000.ts"));
        assertTrue(result.content().contains("seg0006.ts"));
        assertTrue(result.content().contains("seg0008.ts"));
    }

    @Test
    public void concurrentPurificationKeepsEachResultCountPairedWithItsPlaylist() throws Exception {
        String adPlaylist = "#EXTM3U\n#EXT-X-CUE-OUT:6\n"
                + "#EXTINF:6.000,\nhttps://cdn.example/vod/ad.ts\n"
                + "#EXT-X-CUE-IN\n#EXTINF:6.000,\nhttps://cdn.example/vod/main.ts\n";
        String cleanPlaylist = "#EXTM3U\n#EXTINF:6.000,\nhttps://cdn.example/vod/main.ts\n";
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<M3u8.PurifyResult> withAd = new AtomicReference<>();
        AtomicReference<M3u8.PurifyResult> clean = new AtomicReference<>();
        Thread first = new Thread(() -> {
            await(start);
            withAd.set(M3u8.purifyWithResult("https://cdn.example/vod/a.m3u8", adPlaylist));
        });
        Thread second = new Thread(() -> {
            await(start);
            clean.set(M3u8.purifyWithResult("https://cdn.example/vod/b.m3u8", cleanPlaylist));
        });
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(1, withAd.get().removedSegments());
        assertEquals(0, clean.get().removedSegments());
        assertFalse(withAd.get().content().contains("ad.ts"));
        assertTrue(clean.get().content().contains("main.ts"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static String uniformCadencePlaylist(String[] groupDurations) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        int segment = 0;
        for (String duration : groupDurations) {
            playlist.append("#EXT-X-DISCONTINUITY\n");
            for (int i = 0; i < 5; i++) {
                playlist.append("#EXTINF:").append(duration).append(",\n")
                        .append("https://cdn.example/vod/seg")
                        .append(String.format(java.util.Locale.ROOT, "%04x", segment++))
                        .append(".ts\n");
            }
        }
        return playlist.append("#EXT-X-ENDLIST\n").toString();
    }
}
