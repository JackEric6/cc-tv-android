package com.github.tvbox.osc.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class GuaziAdapterTest {

    @Test
    public void searchMatchesTitleAndIgnoresWhitespaceAndPunctuation() {
        assertTrue(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "沉默的荣耀"));
        assertTrue(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "《沉默 的 荣耀》（2025）"));
    }

    @Test
    public void searchRejectsUnrelatedOrMissingTitles() {
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", "荣耀之外"));
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", ""));
        assertFalse(GuaziAdapter.matchesSearchTitle("沉默的荣耀", null));
        assertFalse(GuaziAdapter.matchesSearchTitle("！！！", "任意影片"));
    }

    @Test
    public void homepagePutsSeptemberHotDramaAndLatestHotMoviesFirst() {
        List<String> ordered = GuaziAdapter.orderHomeTitlesForTest(Arrays.asList(
                "最新热播电影", "普通栏目", "9月重磅新剧", "热播综艺"));
        assertEquals(Arrays.asList("9月重磅新剧", "最新热播电影", "普通栏目",
                "热播综艺"), ordered);
    }

    @Test
    public void homepageKeepsTheRequestedFourteenLabels() {
        List<String> titles = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) titles.add("栏目" + i);
        assertEquals(14, GuaziAdapter.fixedHomeTitlesForTest().size());
        assertEquals(14, GuaziAdapter.orderHomeTitlesForTest(titles).size());
    }

    @Test
    public void homepageUsesTheRequestedShowIdsAndNamesInOrder() {
        assertEquals(Arrays.asList(
                "74151", "46296", "19260", "17613", "130", "10772", "24058",
                "24066", "24057", "24067", "38579", "16560", "8", "6663"),
                GuaziAdapter.fixedHomeIdsForTest());
        assertEquals(Arrays.asList(
                "9月重磅新剧", "最新热播电影", "TC抢先看", "豆瓣电影TOP250", "Netflix新片推荐",
                "入站必追?累计2亿追番", "爱奇艺剧场", "优酷剧场", "腾讯剧场", "芒果剧场",
                "大厂VIP短剧专区", "历届奥斯卡最佳影片", "冷门悬疑佳片Top20", "近期热门综艺"),
                GuaziAdapter.fixedHomeTitlesForTest());
    }
}
