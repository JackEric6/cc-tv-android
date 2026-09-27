package com.github.tvbox.osc.viewmodel;

import static org.junit.Assert.assertEquals;

import okhttp3.HttpUrl;
import org.junit.Test;

public class SourceViewModelTest {
    @Test
    public void juliangUsesOfficialSiteAsPlaybackReferer() {
        assertEquals("https://juliang.app/",
                SourceViewModel.cmsRefererForSourceKey("juliang"));
    }

    @Test
    public void yparseTokenUsesOfficialResolverPage() {
        assertEquals("https://jx.yparse.com/index.php?url=YparseToken123",
                SourceViewModel.normalizeCmsPlaybackUrl("yparse", "YparseToken123"));
    }

    @Test
    public void yparseAndIkunKeepOfficialPlaybackReferers() {
        assertEquals("https://yparse.com/",
                SourceViewModel.cmsRefererForSourceKey("yparse"));
        assertEquals("https://ikunzy.com/",
                SourceViewModel.cmsRefererForSourceKey("ikunzy"));
    }

    @Test
    public void cmsSearchUsesOriginalDetailActionAndKeepsFixedParams() {
        String url = SourceViewModel.buildSearchUrl(
                "https://example.com/api?source=demo&ac=list&wd=old",
                "沉默的荣耀",
                "detail");
        HttpUrl parsed = HttpUrl.get(url);
        assertEquals("demo", parsed.queryParameter("source"));
        assertEquals("detail", parsed.queryParameter("ac"));
        assertEquals("沉默的荣耀", parsed.queryParameter("wd"));
    }
}
