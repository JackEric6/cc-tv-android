package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class DefaultConfig {

    public static List<MovieSort.SortData> adjustSort(String sourceKey, List<MovieSort.SortData> list, boolean withMy) {
        List<MovieSort.SortData> sourceData = new ArrayList<>();
        if (list != null) {
            for (MovieSort.SortData sortData : list) {
                if (sortData == null) continue;
                if (sortData.filters == null) sortData.filters = new ArrayList<>();
                sourceData.add(sortData);
            }
        }

        // 先依据资源站自己返回的父子关系裁掉父入口,再应用配置中的名称筛选。
        List<MovieSort.SortData> leafData = filterParentCategories(sourceData);
        List<MovieSort.SortData> data = new ArrayList<>();
        if (sourceKey != null) {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            ArrayList<String> categories = sb == null ? null : sb.getCategories();
            if (categories != null && !categories.isEmpty()) {
                for (String cate : categories) {
                    for (MovieSort.SortData sortData : leafData) {
                        if (sortData.name != null && sortData.name.equals(cate) && !data.contains(sortData)) {
                            data.add(sortData);
                        }
                    }
                }
            } else {
                data.addAll(leafData);
            }
        } else {
            data.addAll(leafData);
        }

        sortCategories(data);
        if (withMy) data.add(0, new MovieSort.SortData("my0", "主页"));
        return data;
    }

    /**
     * 只隐藏有真实子节点的父分类;无父子字段的资源站按扁平分类处理。
     * 没有层级字段时,仅在存在其它分类的前提下过滤明显的通用父标签。
     */
    static List<MovieSort.SortData> filterParentCategories(List<MovieSort.SortData> input) {
        List<MovieSort.SortData> source = input == null ? new ArrayList<>() : new ArrayList<>(input);
        Set<String> parentIds = new HashSet<>();
        boolean hasHierarchy = false;
        for (MovieSort.SortData item : source) {
            if (item == null) continue;
            String parentId = normalizeId(item.parentId);
            if (isRootParentId(parentId)) continue;
            hasHierarchy = true;
            if (!parentId.isEmpty()) parentIds.add(parentId);
        }
        if (hasHierarchy) {
            List<MovieSort.SortData> result = new ArrayList<>();
            for (MovieSort.SortData item : source) {
                if (item == null) continue;
                if (parentIds.contains(normalizeId(item.id))) continue;
                result.add(item);
            }
            return result;
        }

        List<MovieSort.SortData> nonParents = new ArrayList<>();
        for (MovieSort.SortData item : source) {
            if (item != null && !isObviousParentName(item.name)) nonParents.add(item);
        }
        return nonParents;
    }

    private static boolean isRootParentId(String parentId) {
        String value = normalizeId(parentId);
        if (value.isEmpty()) return true;
        return "0".equals(value) || "-1".equals(value) || "null".equalsIgnoreCase(value);
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    private static boolean isObviousParentName(String name) {
        String value = normalizeCategoryName(name);
        return "电影".equals(value) || "电影片".equals(value)
                || "电视剧".equals(value) || "连续剧".equals(value)
                || "剧集".equals(value) || "影视".equals(value) || "影视剧".equals(value)
                || "电影类".equals(value) || "电视剧类".equals(value)
                || "综艺".equals(value) || "综艺片".equals(value) || "综艺类".equals(value)
                || "动漫".equals(value) || "动漫类".equals(value);
    }

    private static void sortCategories(List<MovieSort.SortData> data) {
        if (data == null) return;
        for (MovieSort.SortData item : data) {
            if (item != null) item.sort = categoryRank(item == null ? "" : item.name);
        }
        // Collections.sort/TimSort 稳定,同优先级保持资源站原始顺序。
        Collections.sort(data, Comparator.comparingInt((MovieSort.SortData item) ->
                item == null ? Integer.MAX_VALUE : item.sort));
    }

    private static int categoryRank(String name) {
        String value = normalizeCategoryName(name);
        if (value.contains("伦理") || value.contains("理论片")) return 10000;
        if (equalsAny(value, "国产剧", "大陆剧", "内地剧", "国产电视剧")) return 0;
        if (equalsAny(value, "喜剧片", "喜剧")) return 10;
        if (equalsAny(value, "爱情片", "爱情")) return 20;
        if (equalsAny(value, "动作片", "动作")) return 30;
        if (equalsAny(value, "科幻片", "科幻")) return 40;
        if (equalsAny(value, "恐怖片", "恐怖")) return 50;
        if (equalsAny(value, "剧情片", "剧情")) return 60;
        if (equalsAny(value, "战争片", "战争")) return 70;
        if (value.contains("纪录片") || value.contains("记录片")) return 80;
        if (value.contains("动画") || value.contains("动漫")) return 90;
        if (value.contains("综艺")) return 100;
        if (value.contains("体育") || value.contains("赛事")) return 110;
        if (value.contains("短剧")) return 120;
        return 1000;
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) if (candidate.equals(value)) return true;
        return false;
    }

    private static String normalizeCategoryName(String name) {
        return name == null ? "" : name.trim().replace(" ", "").replace("\u3000", "").toLowerCase(Locale.ROOT);
    }

    public static int getAppVersionCode(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static String getAppVersionName(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 后缀
     *
     * @param name
     * @return
     */
    public static String getFileSuffix(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int endP = name.lastIndexOf(".");
        return endP > -1 ? name.substring(endP) : "";
    }

    /**
     * 获取文件的前缀
     *
     * @param fileName
     * @return
     */
    public static String getFilePrefixName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        int start = fileName.lastIndexOf(".");
        return start > -1 ? fileName.substring(0, start) : fileName;
    }

    private static final Pattern snifferMatch = Pattern.compile(
            "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\\?.*|" +
            "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|" +
            "http((?!http).)*?video/tos*|" +
            "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
            "http((?!http).)*?default\\.ixigua\\.com/.*|" +
            "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
            "http.*?/player/m3u8play\\.php\\?url=.*|" +
            "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
            "http.*?/playlist/m3u8/\\?vid=.*|" +
            "http.*?\\.php\\?type=m3u8&.*|" +
            "http.*?/download.aspx\\?.*|" +
            "http.*?/api/up_api.php\\?.*|" +
            "https.*?\\.66yk\\.cn.*|" +
            "http((?!http).)*?netease\\.com/file/.*"
    );
    public static boolean isVideoFormat(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mp4")
                || lowerPath.endsWith(".flv") || lowerPath.endsWith(".mpd")
                || lowerPath.endsWith(".ts")) {
            return true;
        }
        if (snifferMatch.matcher(url).find()) return true;
        return false;
    }


    public static String safeJsonString(JsonObject obj, String key, String defaultVal) {
        try {
            if (obj.has(key)){
                return obj.get(key).isJsonObject() || obj.get(key).isJsonArray()?obj.get(key).toString().trim():obj.getAsJsonPrimitive(key).getAsString().trim();
            }
            else
                return defaultVal;
        } catch (Throwable th) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a plain string, use default");
        }
        return defaultVal;
    }

    public static int safeJsonInt(JsonObject obj, String key, int defaultVal) {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).getAsInt();
            else
                return defaultVal;
        } catch (Throwable th) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a number, use default");
        }
        return defaultVal;
    }

    public static ArrayList<String> safeJsonStringList(JsonObject obj, String key) {
        ArrayList<String> result = new ArrayList<>();
        try {
            if (obj.has(key)) {
                if (obj.get(key).isJsonObject()) {
                    result.add(obj.get(key).getAsString());
                } else {
                    for (JsonElement opt : obj.getAsJsonArray(key)) {
                        result.add(opt.getAsString());
                    }
                }
            }
        } catch (Throwable th) {
            LOG.d("DefaultConfig", "json key '" + key + "' not a string list, use empty");
        }
        return result;
    }

    public static String checkReplaceProxy(String urlOri) {
        if (urlOri.startsWith("proxy://"))
            return urlOri.replace("proxy://", ControlManager.get().getAddress(true) + "proxy?");
        return urlOri;
    }

    private static final List<String> NO_AD_KEYWORDS = Arrays.asList(
            "tx", "youku", "qq","qiyi", "letv", "leshi","sohu", "mgtv", "bilibili", "imgo","优酷", "芒果", "腾讯", "奇艺"
    );

    public static boolean noAd(String flag) {
        if (flag == null || flag.isEmpty()) return false;
        for (String keyword : NO_AD_KEYWORDS) {
            if (flag.equals(keyword) || flag.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
