package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OfflineMediaTransformerTest {
    @Test
    public void recognizesDirectConvertibleMediaAndQueryType() {
        assertTrue(OfflineMediaTransformer.isConvertibleMediaUrl("https://cdn.example/a.ts?token=1"));
        assertTrue(OfflineMediaTransformer.isConvertibleMediaUrl("https://cdn.example/play?id=1&type=mpd"));
        assertTrue(OfflineMediaTransformer.isConvertibleMediaUrl("https://cdn.example/a.flv"));
        assertTrue(OfflineMediaTransformer.isConvertibleMediaUrl("https://cdn.example/a.mkv"));
    }

    @Test
    public void rejectsParserPagesAndNonHttpUrls() {
        assertFalse(OfflineMediaTransformer.isConvertibleMediaUrl("https://site.example/player?id=1"));
        assertFalse(OfflineMediaTransformer.isConvertibleMediaUrl("magnet:?xt=urn:btih:abc"));
        assertFalse(OfflineMediaTransformer.isConvertibleMediaUrl("https://cdn.example/a.mp4"));
    }
}
