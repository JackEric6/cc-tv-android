package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
}
