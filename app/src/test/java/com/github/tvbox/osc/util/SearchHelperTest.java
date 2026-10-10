package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 搜索源选择"是否过期"的判定单测(2026-09-13 回归)。
 *
 * <p>缺陷背景:该选择按**源 key** 记,而源 key 属于具体的源集合。换点播源后旧 key 在新源列表里不存在,
 * 若仍拿它过滤,搜索会被悄悄窄化到"新旧源共有的那几个源"(用户实测:切源后只剩「玩偶4K」可搜,重启恢复)。
 */
public class SearchHelperTest {

    private static Set<String> keys(String... keys) {
        return new HashSet<>(java.util.Arrays.asList(keys));
    }

    private static HashMap<String, String> checked(String... keys) {
        HashMap<String, String> map = new HashMap<>();
        for (String key : keys) map.put(key, "1");
        return map;
    }

    @Test
    public void nullOrEmptySelection_isNeverStale() {
        // 没限制(搜索全部可搜源)⇒ 与源集合无关,永远谈不上过期
        assertFalse(SearchHelper.isSelectionStale(null, keys("a", "b")));
        assertFalse(SearchHelper.isSelectionStale(new HashMap<>(), keys("a", "b")));
        // 源列表为空时也不该把"没限制"误判成过期
        assertFalse(SearchHelper.isSelectionStale(null, keys()));
    }

    @Test
    public void selectionMatchesCurrentSources_isNotStale() {
        assertFalse(SearchHelper.isSelectionStale(checked("a", "b"), keys("a", "b", "c")));
    }

    @Test
    public void selectionFromAnotherSourceSet_isStale() {
        // 换源后:选择里是旧源的 key,当前源列表换成了另一批
        assertTrue(SearchHelper.isSelectionStale(checked("old1", "old2"), keys("new1", "new2")));
    }

    @Test
    public void partiallyMatchingSelection_isStale() {
        // 用户实测形态:新旧源共有一个 key(如"玩偶4K"),其余全对不上。
        // 只要有一个 key 对不上就必须判过期 —— 否则搜索被窄化到只剩那个共有源
        assertTrue(SearchHelper.isSelectionStale(checked("shared", "oldOnly"), keys("shared", "newOnly")));
    }

    @Test
    public void selectionWithAllKeysUnknown_isStale() {
        assertTrue(SearchHelper.isSelectionStale(checked("x"), keys()));
    }

    @Test
    public void searchVerifiedAllowlist_excludesUnverifiedSources() {
        assertTrue(SearchHelper.isSearchVerified("xgzy"));
        assertTrue(SearchHelper.isSearchVerified("yzzy"));
        assertTrue(SearchHelper.isSearchVerified("ikanbot"));
        assertTrue(SearchHelper.isSearchVerified("fhw88"));
        assertFalse(SearchHelper.isSearchVerified("ikunzy"));
        assertTrue(SearchHelper.isSearchVerified("lzi"));
        assertTrue(SearchHelper.isSearchVerified("ffzy"));
        assertFalse(SearchHelper.isSearchVerified("qiumu"));
        assertTrue(SearchHelper.isSearchVerified("cechi"));
        assertTrue(SearchHelper.isSearchVerified("zanpian"));
        assertTrue(SearchHelper.isSearchVerified("cz4k"));
        assertTrue(SearchHelper.isSearchVerified("fan_nomi"));
        assertTrue(SearchHelper.isSearchVerified("fan_moli"));
        assertTrue(SearchHelper.isSearchVerified("fan_cheese"));
        assertTrue(SearchHelper.isSearchVerified("fan_jianpian"));
        assertFalse(SearchHelper.isSearchVerified("fan_guangying"));
        assertFalse(SearchHelper.isSearchVerified("fan_libbo"));
        assertTrue(SearchHelper.isSearchVerified("cms_baidu"));
        assertFalse(SearchHelper.isSearchVerified("cms_liangzi"));
        assertFalse(SearchHelper.isSearchVerified("cms_feifan"));
        assertFalse(SearchHelper.isSearchVerified("cms_haiwaikan"));
        assertTrue(SearchHelper.isSearchVerified("cms_baofeng"));
        assertFalse(SearchHelper.isSearchVerified("cms_suoni"));
        assertFalse(SearchHelper.isSearchVerified("cms_kuaifan"));
        assertFalse(SearchHelper.isSearchVerified("cms_leshi"));
        assertFalse(SearchHelper.isSearchVerified("fan_bit"));
        assertFalse(SearchHelper.isSearchVerified("fan_hot"));
        assertFalse(SearchHelper.isSearchVerified("fan_juquan"));
        assertFalse(SearchHelper.isSearchVerified("beike"));
        assertFalse(SearchHelper.isSearchVerified("meitan"));
        assertFalse(SearchHelper.isSearchVerified("haier"));
        assertFalse(SearchHelper.isSearchVerified("shandian"));
        assertFalse(SearchHelper.isSearchVerified("sony"));
        assertFalse(SearchHelper.isSearchVerified("yparse"));
    }

    @Test
    public void cz4kWebResultsCanAppearWithoutAUsablePoster() {
        assertTrue(SearchHelper.allowsPosterlessSearchResult("cz4k"));
        assertFalse(SearchHelper.allowsPosterlessSearchResult("unknown"));
    }

    @Test
    public void bundledSourcesAreAddedToAnExistingSearchSelection() {
        HashMap<String, String> selected = checked(
                "xgzy", "lzi", "ffzy", "qiumu", "beike",
                "fan_guangying", "cms_feifan");
        HashMap<String, String> merged = SearchHelper.mergeBundledSearchSources(
                selected, keys("xgzy", "lzi", "ffzy", "qiumu", "beike", "cechi", "zanpian",
                "meitan", "cz4k", "fan_bit", "fan_nomi", "fan_hot",
                        "fan_moli", "fan_cheese", "fan_juquan", "fan_jianpian",
                        "fan_guangying", "fan_libbo", "cms_baidu", "cms_liangzi",
                        "cms_feifan", "cms_haiwaikan", "cms_baofeng", "cms_suoni",
                        "cms_kuaifan", "cms_leshi",
                        "unknown"));

        assertTrue(merged.containsKey("xgzy"));
        assertTrue(merged.containsKey("lzi"));
        assertTrue(merged.containsKey("ffzy"));
        assertFalse(merged.containsKey("qiumu"));
        assertFalse(merged.containsKey("beike"));
        assertTrue(merged.containsKey("cechi"));
        assertTrue(merged.containsKey("zanpian"));
        assertTrue(merged.containsKey("cz4k"));
        assertTrue(merged.containsKey("fan_nomi"));
        assertTrue(merged.containsKey("fan_moli"));
        assertTrue(merged.containsKey("fan_cheese"));
        assertTrue(merged.containsKey("fan_jianpian"));
        assertFalse(merged.containsKey("fan_guangying"));
        assertFalse(merged.containsKey("fan_libbo"));
        assertTrue(merged.containsKey("cms_baidu"));
        assertFalse(merged.containsKey("cms_liangzi"));
        assertFalse(merged.containsKey("cms_feifan"));
        assertFalse(merged.containsKey("cms_haiwaikan"));
        assertTrue(merged.containsKey("cms_baofeng"));
        assertFalse(merged.containsKey("cms_suoni"));
        assertFalse(merged.containsKey("cms_kuaifan"));
        assertFalse(merged.containsKey("cms_leshi"));
        assertFalse(merged.containsKey("fan_bit"));
        assertFalse(merged.containsKey("fan_hot"));
        assertFalse(merged.containsKey("fan_juquan"));
        assertFalse(merged.containsKey("meitan"));
        assertFalse(merged.containsKey("unknown"));
    }

}
