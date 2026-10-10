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
    public void guaziUsesTheIosPlaybackUserAgent() {
        assertEquals("KSPlayer", SourceViewModel.cmsPlaybackUserAgentForSourceKey("guazi"));
        assertEquals("KSPlayer", SourceViewModel.cmsPlaybackUserAgentForSourceKey("Guazi"));
        assertEquals("", SourceViewModel.cmsPlaybackUserAgentForSourceKey("xigua"));
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
    public void addedWebSitesUseTheirOwnPlaybackReferers() {
        assertEquals("https://old.sliiic.org/",
                SourceViewModel.cmsRefererForSourceKey("qiumu"));
        assertEquals("https://www.hmjy100.com/",
                SourceViewModel.cmsRefererForSourceKey("meitan"));
        assertEquals("https://www.wljingyan.com/",
                SourceViewModel.cmsRefererForSourceKey("jingyan"));
        assertEquals("https://www.kdndt.net/",
                SourceViewModel.cmsRefererForSourceKey("kaida"));
        assertEquals("http://www.knshuichuli.com/",
                SourceViewModel.cmsRefererForSourceKey("shuicao"));
        assertEquals("https://m.syshuoyuan.com/",
                SourceViewModel.cmsRefererForSourceKey("hongguo"));
        assertEquals("https://shanghai.365che.net/",
                SourceViewModel.cmsRefererForSourceKey("laoniu"));
        assertEquals("https://www.nmgflw.com/",
                SourceViewModel.cmsRefererForSourceKey("ningmeng"));
        assertEquals("http://www.chgxdq.com/",
                SourceViewModel.cmsRefererForSourceKey("xingchen"));
        assertEquals("https://yz.sdhfcj.com/",
                SourceViewModel.cmsRefererForSourceKey("qingpingguo"));
        assertEquals("https://www.sczxsx.com/",
                SourceViewModel.cmsRefererForSourceKey("zhongxing"));
        assertEquals("https://www.nbhaierxin.com/",
                SourceViewModel.cmsRefererForSourceKey("haier"));
        assertEquals("https://www.zzhuilv.cn/",
                SourceViewModel.cmsRefererForSourceKey("huilv"));
        assertEquals("http://m.sjtx-oa.com/",
                SourceViewModel.cmsRefererForSourceKey("shiji"));
        assertEquals("http://www.lanyajc.com/",
                SourceViewModel.cmsRefererForSourceKey("cechi"));
        assertEquals("http://www.ikang.org/",
                SourceViewModel.cmsRefererForSourceKey("kuaikan"));
        assertEquals("http://www.fjsycjs.com/",
                SourceViewModel.cmsRefererForSourceKey("wushuang"));
        assertEquals("https://www.zanpian.org/",
                SourceViewModel.cmsRefererForSourceKey("zanpian"));
        assertEquals("https://www.88ystv.com/",
                SourceViewModel.cmsRefererForSourceKey("ys88"));
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
