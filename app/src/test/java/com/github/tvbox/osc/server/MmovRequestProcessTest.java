package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MmovRequestProcessTest {
    @Test
    public void extractsM3u8AddressFromMmovPlayerPage() {
        String html = "<script>var videoSrc = 'https://cdn.test/path/index.m3u8?token=abc';</script>";
        assertEquals("https://cdn.test/path/index.m3u8?token=abc",
                MmovRequestProcess.extractVideoUrl(html));
    }

    @Test
    public void extractsEscapedVideoAddress() {
        String html = "<script>var videoSrc = \"https:\\/\\/cdn.test\\/index.m3u8\";</script>";
        assertEquals("https://cdn.test/index.m3u8",
                MmovRequestProcess.extractVideoUrl(html));
    }

    @Test
    public void rejectsNonHttpPlayerAddress() {
        assertEquals("", MmovRequestProcess.extractVideoUrl(
                "<script>var videoSrc = 'javascript:alert(1)';</script>"));
    }
}
