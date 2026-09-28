package com.github.tvbox.osc.util;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 搜索源选择(哪些源参与全站搜索)。
 *
 * <p>存储:`SOURCES_FOR_SEARCH` 是一张「点播源地址 → {源 key → "1"}」的表 —— 每个源集合各自记住自己的勾选。
 * 表里没有当前地址的记录(或记录为空)= 没限制,搜索当前源集合的**全部可搜源**。
 */
public class SearchHelper {

    /**
     * 2026-09-24 搜索探测中确认能返回有效影视数据的源。
     *
     * <p>这里只控制“搜索页参与搜索”，不删除或禁用配置源；首页、分类、详情和播放仍由
     * SourceBean 的原始配置决定。明确返回“暂不支持搜索”、403、空结果，或尚未完成探测的
     * 源先不进入搜索页，待后续按官网帮助页完成端到端验证后再加入。
     */
    private static final Set<String> SEARCH_VERIFIED_SOURCE_KEYS = new HashSet<>();
    private static final Set<String> BUNDLED_SEARCH_SOURCE_KEYS = new HashSet<>();
    private static final Set<String> WEB_CMS_SOURCE_KEYS = new HashSet<>();
    private static final String BUNDLED_SEARCH_MIGRATION_KEY = "xgzy_bundled_search_sources_v1";

    static {
        String[] verified = {
                "xgzy", "360zy", "jisu", "lzi", "ffzy", "hongniu",
                "jinying", "guangsu", "huya", "juliang", "iqiyi", "maoyan",
                "bfzy", "zuida", "yzzy", "haohua", "xinlang", "dyttzy",
                "ukuzy", "suboziyuan", "bdzy", "dzzy", "ryzy",
                // Built-in sources with verified search adapters.
                "ikanbot", "fhw88",
                // Web CMS sources bundled with this APK.
                "qiumu", "beike", "meitan", "jingyan", "kaida", "shuicao",
                "hongguo", "laoniu", "ningmeng", "xingchen", "qingpingguo",
                "zhongxing", "haier", "huilv", "shiji", "cechi", "kuaikan",
                "wushuang", "zanpian", "ys88"
        };
        for (String key : verified) SEARCH_VERIFIED_SOURCE_KEYS.add(key);

        String[] bundled = {
                "ikanbot", "fhw88",
                "qiumu", "beike", "meitan", "jingyan", "kaida", "shuicao",
                "hongguo", "laoniu", "ningmeng", "xingchen", "qingpingguo",
                "zhongxing", "haier", "huilv", "shiji", "cechi", "kuaikan",
                "wushuang", "zanpian", "ys88"
        };
        for (String key : bundled) BUNDLED_SEARCH_SOURCE_KEYS.add(key);

        String[] webCms = {
                "qiumu", "beike", "meitan", "jingyan", "kaida", "shuicao",
                "hongguo", "laoniu", "ningmeng", "xingchen", "qingpingguo",
                "zhongxing", "haier", "huilv", "shiji", "cechi", "kuaikan",
                "wushuang", "zanpian", "ys88"
        };
        for (String key : webCms) WEB_CMS_SOURCE_KEYS.add(key);
    }

    /** 当前版本经过探测、允许出现在搜索页的源。 */
    public static boolean isSearchVerified(String sourceKey) {
        return sourceKey != null && SEARCH_VERIFIED_SOURCE_KEYS.contains(sourceKey);
    }

    /** 网页结果已按关键词校验，允许无海报时先显示结果卡片。 */
    public static boolean allowsPosterlessSearchResult(String sourceKey) {
        return sourceKey != null && WEB_CMS_SOURCE_KEYS.contains(sourceKey);
    }

    /**
     * 读取当前地址对应的搜索源选择。
     *
     * @return null 或空表 ⇒ 调用方按"全部可搜源"处理
     */
    public static HashMap<String, String> getSourcesForSearch() {
        HashMap<String, String> mCheckSources;
        try {
            String api = KV.get(HawkConfig.API_URL, "");
            if (api.isEmpty()) return null;
            HashMap<String, String> allSources = getSources();
            if (allSources.isEmpty()) return allSources;

            // 不带默认值读取,让 KVKeySpec 按完整嵌套泛型还原 HashMap。
            HashMap<String, HashMap<String, String>> mCheckSourcesForApi =
                    KV.get(HawkConfig.SOURCES_FOR_SEARCH);
            mCheckSources = mCheckSourcesForApi == null ? null : mCheckSourcesForApi.get(api);
            if (mCheckSources == null || mCheckSources.isEmpty()) {
                markBundledSearchMigration(api);
                return allSources;
            }

            String before = mCheckSources.toString();
            if (!isBundledSearchMigrationApplied(api)) {
                mCheckSources = mergeBundledSearchSources(mCheckSources, allSources.keySet());
                if (!before.equals(mCheckSources.toString())) {
                    mCheckSourcesForApi.put(api, mCheckSources);
                    KV.put(HawkConfig.SOURCES_FOR_SEARCH, mCheckSourcesForApi);
                }
                markBundledSearchMigration(api);
            }
        } catch (Exception e) {
            return null;
        }

        // 清理旧版本保存的已隐藏源，避免用户切换配置后它们继续参与搜索。
        mCheckSources.keySet().removeIf(key -> !isSearchVerified(key));
        return mCheckSources;
    }

    /**
     * 将本 APK 新增的网页源补进旧的显式搜索选择。
     *
     * <p>只在迁移版本首次执行时调用，因此用户后续在“搜索设置”里取消某个源不会被反复加回。
     * 同时清理当前源集合中已经不存在的旧 key。</p>
     */
    static HashMap<String, String> mergeBundledSearchSources(
            HashMap<String, String> selected, Set<String> currentSourceKeys) {
        HashMap<String, String> merged = new HashMap<>();
        if (selected != null) {
            for (String key : selected.keySet()) {
                if (currentSourceKeys.contains(key) && isSearchVerified(key)) {
                    merged.put(key, "1");
                }
            }
        }
        for (String key : BUNDLED_SEARCH_SOURCE_KEYS) {
            if (currentSourceKeys.contains(key)) merged.put(key, "1");
        }
        return merged;
    }

    private static boolean isBundledSearchMigrationApplied(String api) {
        String applied = KV.get(BUNDLED_SEARCH_MIGRATION_KEY, "");
        for (String item : applied.split("\n")) {
            if (api.equals(item)) return true;
        }
        return false;
    }

    private static void markBundledSearchMigration(String api) {
        if (isBundledSearchMigrationApplied(api)) return;
        String applied = KV.get(BUNDLED_SEARCH_MIGRATION_KEY, "");
        KV.put(BUNDLED_SEARCH_MIGRATION_KEY,
                applied.isEmpty() ? api : applied + "\n" + api);
    }

    /**
     * 判断一份选择是否还"对得上当前源列表"。
     *
     * <p>存在的理由(2026-09-13 修):选择是按**源 key** 记的,而源 key 属于**具体的源集合**。
     * 换了点播源之后,旧选择里的 key 在新源列表里基本都不存在 —— 若还拿它去过滤,结果就是
     * "只搜到新旧共有的那一个源"(用户实测:切源后只剩「玩偶4K」能搜到),重启才恢复。
     *
     * <p>判据:选择里的每个 key 都必须在当前源列表里存在。只要有一个对不上,这份选择就已过期。
     */
    public static boolean isSelectionStale(HashMap<String, String> checked) {
        Set<String> liveKeys = new HashSet<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            liveKeys.add(bean.getKey());
        }
        return isSelectionStale(checked, liveKeys);
    }

    /** 纯判定(与 Android 解耦,便于单测):选择是否已不匹配给定的源 key 集合 */
    public static boolean isSelectionStale(HashMap<String, String> checked, Set<String> liveSourceKeys) {
        if (checked == null || checked.isEmpty()) return false; // 没限制,谈不上过期
        for (String checkedKey : checked.keySet()) {
            if (!liveSourceKeys.contains(checkedKey)) return true;
        }
        return false;
    }

    /** 当前源集合里所有可搜源(key → "1"),用于"未限制"时的选择 */
    public static HashMap<String, String> getSources() {
        HashMap<String, String> mCheckSources = new HashMap<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (!bean.isSearchable() || !isSearchVerified(bean.getKey())) {
                continue;
            }
            mCheckSources.put(bean.getKey(), "1");
        }
        return mCheckSources;
    }

}
