package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** 净化代理槽位必须能跨过预载/重试产生的多个新槽位保持可读。 */
public class RemoteServerM3u8SlotTest {

    @Test
    public void keepsAnActiveSlotAfterManyLaterPurifications() {
        String firstContent = "#EXTM3U\n#EXTINF:6,\nfirst.ts\n";
        String firstKey = RemoteServer.putM3u8Content(firstContent);
        for (int i = 0; i < 12; i++) {
            RemoteServer.putM3u8Content("#EXTM3U\n#EXTINF:6,\nnext-" + i + ".ts\n");
        }

        assertNotNull(firstKey);
        assertEquals(firstContent, RemoteServer.getM3u8Content(firstKey));
    }
}
