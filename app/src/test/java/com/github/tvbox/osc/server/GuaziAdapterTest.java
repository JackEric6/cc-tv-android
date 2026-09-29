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
                "最新热播电影", "普通栏目", "9月热榜新剧", "热播综艺"));
        assertEquals(Arrays.asList("9月热榜新剧", "最新热播电影", "普通栏目",
                "热播综艺"), ordered);
    }

    @Test
    public void homepageKeepsAtMostFifteenLabels() {
        List<String> titles = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) titles.add("栏目" + i);
        assertEquals(15, GuaziAdapter.orderHomeTitlesForTest(titles).size());
    }
}
