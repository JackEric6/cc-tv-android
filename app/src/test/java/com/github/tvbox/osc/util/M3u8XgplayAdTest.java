package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class M3u8XgplayAdTest {
    @Test
    public void preservesUnclassifiedDiscontinuityBlocks() {
        String playlist = playlist("xgplay-main", "mid-1.ts", "mid-2.ts", "xgplay-tail");

        String purified = M3u8.purify("https://svip.xgplay13.com/vod/", playlist);

        assertEquals(0, M3u8.currentAdCount);
        assertTrue(purified.contains("mid-1.ts"));
        assertTrue(purified.contains("mid-2.ts"));
        assertTrue(purified.contains("xgplay-main-0.ts"));
        assertTrue(purified.contains("xgplay-tail-0.ts"));
    }

    @Test
    public void keepsShortBlocksWithoutAdMarkers() {
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:5,\nshort.ts\n#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:5,\nshort-2.ts\n#EXT-X-ENDLIST\n";

        String purified = M3u8.purify("https://svip.xgplay13.com/vod/", playlist);

        assertTrue(purified.contains("short.ts"));
        assertTrue(purified.contains("short-2.ts"));
    }

    private String playlist(String prefix, String ad1, String ad2, String tail) {
        StringBuilder value = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:6\n");
        for (int i = 0; i < 3; i++) {
            value.append("#EXTINF:4,\n").append(prefix).append('-').append(i).append(".ts\n");
        }
        value.append("#EXT-X-DISCONTINUITY\n#EXTINF:2,\n").append(ad1)
                .append("\n#EXTINF:2,\n").append(ad2)
                .append("\n#EXT-X-DISCONTINUITY\n");
        for (int i = 0; i < 3; i++) {
            value.append("#EXTINF:4,\n").append(tail).append('-').append(i).append(".ts\n");
        }
        return value.append("#EXT-X-ENDLIST\n").toString();
    }
}
