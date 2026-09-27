package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;

import okhttp3.HttpUrl;
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

    @Test
    public void keepsSearchKeywordAsQueryParameter() {
        String url = MmovRequestProcess.buildUrl(
                "https://vn.mmov.app",
                "/vodsearch/-------------.html?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80");
        HttpUrl parsed = HttpUrl.get(url);
        assertEquals("/vodsearch/-------------.html", parsed.encodedPath());
        assertEquals("沉默的荣耀", parsed.queryParameter("wd"));
    }

    @Test
    public void keepsCategoryPaginationQueryParameter() {
        String url = MmovRequestProcess.buildUrl(
                "https://vn.mmov.app", "/type/1.html?page=2");
        HttpUrl parsed = HttpUrl.get(url);
        assertEquals("/type/1.html", parsed.encodedPath());
        assertEquals("2", parsed.queryParameter("page"));
    }
}
