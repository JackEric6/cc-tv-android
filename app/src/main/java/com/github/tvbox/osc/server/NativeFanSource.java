package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * spider2.jar 中七个源的原生适配层。
 *
 * <p>这里故意只输出 TVBox 标准 JSON/播放结果，调用方不需要知道源站的页面模板、
 * Android 代理地址或历史 Spider 实现。厂长由用户自己的实现接管，不在本类中处理。</p>
 */
public final class NativeFanSource {
    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final String NOMI = "https://vip.wwgz.cn:5200";
    private static final String NOMI_NBY = "https://api.nbyjson.top:7788/api/?key=tviKuMb3MlqCW1I6gE&url=";
    private static final String BIT = "https://www.bttwo.net";
    private static final String[] BIT_BASES = {
            "https://www.bttwo.net", "https://bttwo.net", "https://www.bttwo.com", "https://bttwo.com"
    };
    private static final String HOT = "https://fan123.hzhnl.com";
    private static final String HOT_DOMAIN_DNS = "https://dns.alidns.com/resolve?name=swrdsfeiujo25sw.cc&type=TXT";
    private static final String HOT_REFERER = "https://fan123.hzhnl.com";
    private static final String MOLI = "http://103.236.72.182:3688";
    private static final String JUQUAN = "https://app.95112475.xyz";
    private static final String JUQUAN_KEY = "5a9w6x58dsq6z3a6";
    private static final String CHEESE_API = "http://caiji.dyttzyapi.com/api.php/provide/vod/from/dyttm3u8/at/m3u8/";
    private static final Pattern M3U8 = Pattern.compile("https?://[^\\\"'<>\\s]+(?:m3u8|mp4)(?:\\?[^\\\"'<>\\s]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NBY = Pattern.compile("NBY-[A-Za-z0-9_+./=-]+(?:\\|[A-Za-z0-9_+./=-]+)?");

    private NativeFanSource() {
    }

    /**
     * 配置源的显示 key 和历史内置 key 不是同一套命名。最新配置使用“比特/热播/剧圈”等中文 key，
     * 旧版内置配置使用 fan_*；所有原生入口统一转换到内部 key，避免落回 type=3 的旧 Spider。
     */
    public static String normalizeKey(String key) {
        if (key == null) return "";
        String value = key.trim();
        if ("fan_bit".equalsIgnoreCase(value) || "比特".equals(value)
                || "Bttwoo".equalsIgnoreCase(value) || "csp_BttwooGuard".equalsIgnoreCase(value)) return "fan_bit";
        if ("fan_nomi".equalsIgnoreCase(value) || "糯米".equals(value)
                || "Nmyswv".equalsIgnoreCase(value) || "csp_NmyswvGuard".equalsIgnoreCase(value)) return "fan_nomi";
        if ("fan_hot".equalsIgnoreCase(value) || "热播".equals(value)
                || "AppTT".equalsIgnoreCase(value) || "csp_AppTTGuard".equalsIgnoreCase(value)) return "fan_hot";
        if ("fan_moli".equalsIgnoreCase(value) || "茉莉".equals(value) || "视界".equals(value)
                || "App99".equalsIgnoreCase(value) || "csp_App99Guard".equalsIgnoreCase(value)) return "fan_moli";
        if ("fan_cheese".equalsIgnoreCase(value) || "奶酪".equals(value)) return "fan_cheese";
        if ("fan_juquan".equalsIgnoreCase(value) || "剧圈".equals(value)
                || "AppSx".equalsIgnoreCase(value) || "csp_AppSxGuard".equalsIgnoreCase(value)) return "fan_juquan";
        if ("fan_jianpian".equalsIgnoreCase(value) || "荐片".equals(value)
                || "JPJ".equalsIgnoreCase(value) || "csp_JPJGuard".equalsIgnoreCase(value)) return "fan_jianpian";
        return value;
    }

    public static boolean isSupported(String key) {
        String normalized = normalizeKey(key);
        return "fan_bit".equals(normalized) || "fan_nomi".equals(normalized) || "fan_hot".equals(normalized)
                || "fan_moli".equals(normalized) || "fan_cheese".equals(normalized)
                || "fan_juquan".equals(normalized) || "fan_jianpian".equals(normalized);
    }

    public static String home(String key) throws Exception {
        key = normalizeKey(key);
        if ("fan_nomi".equals(key)) return nomiHome();
        if ("fan_bit".equals(key)) return bitHome();
        if ("fan_hot".equals(key)) return appHome(key);
        if ("fan_moli".equals(key)) return moliHome();
        if ("fan_cheese".equals(key)) return cheeseHome();
        if ("fan_juquan".equals(key)) return juquanHome();
        if ("fan_jianpian".equals(key)) return jianpianHome();
        return emptyPage().toString();
    }

    public static String category(String key, String id, int page, Map<String, String> filters) throws Exception {
        key = normalizeKey(key);
        if ("fan_nomi".equals(key)) {
            String tid = TextUtils.isEmpty(id) ? "1" : id;
            String url = NOMI + "//index.php?m=vod-list-id-" + encode(tid)
                    + "-pg-" + Math.max(1, page) + "-order--by--class--year--letter--area--lang-.html";
            return nomiPage(fetchDocument(url));
        }
        if ("fan_bit".equals(key)) {
            String url = BIT + "/filter?classify=" + encode(TextUtils.isEmpty(id) ? "1" : id)
                    + "&page=" + Math.max(1, page);
            return bitPage(fetchDocument(url));
        }
        if ("fan_juquan".equals(key)) return juquanCategory(id, page, filters);
        if ("fan_hot".equals(key)) return appPage(key, id, page, filters);
        if ("fan_moli".equals(key)) return genericCmsPage(MOLI, id, page);
        if ("fan_cheese".equals(key)) return cheesePage(id, page);
        if ("fan_jianpian".equals(key)) return jianpianCategory(id, page);
        return emptyPage().toString();
    }

    public static String search(String key, String keyword) throws Exception {
        key = normalizeKey(key);
        if (TextUtils.isEmpty(keyword)) return emptyPage().toString();
        if ("fan_nomi".equals(key)) return nomiPage(fetchDocument(NOMI + "//index.php?m=vod-search&wd=" + encode(keyword)));
        if ("fan_bit".equals(key)) return bitPage(fetchDocument(BIT + "/xssearch?q=" + encode(keyword) + "&f=_all&p=1"));
        if ("fan_juquan".equals(key)) return juquanSearch(keyword);
        if ("fan_hot".equals(key)) return appSearch(key, keyword);
        if ("fan_moli".equals(key)) return genericCmsSearch(MOLI, keyword);
        if ("fan_cheese".equals(key)) return cheeseSearch(keyword);
        if ("fan_jianpian".equals(key)) return jianpianSearch(keyword);
        return emptyPage().toString();
    }

    public static String detail(String key, String id) throws Exception {
        key = normalizeKey(key);
        if ("fan_nomi".equals(key)) return nomiDetail(id);
        if ("fan_bit".equals(key)) return bitDetail(id);
        if ("fan_juquan".equals(key)) return juquanDetail(id);
        if ("fan_hot".equals(key)) return appDetail(key, id);
        if ("fan_moli".equals(key)) return genericCmsDetail(MOLI, id);
        if ("fan_cheese".equals(key)) return cheeseDetail(id);
        if ("fan_jianpian".equals(key)) return jianpianDetail(id);
        return emptyPage().toString();
    }

    public static JSONObject play(String key, String flag, String rawUrl) throws Exception {
        key = normalizeKey(key);
        JSONObject result = new JSONObject();
        result.put("parse", 0);
        result.put("jx", 0);
        result.put("playUrl", "");
        result.put("flag", flag == null ? "" : flag);
        String url = rawUrl == null ? "" : rawUrl.trim();

        if ("fan_nomi".equals(key) || "fan_hot".equals(key)) {
            if (isNby(url)) url = resolveNby(url);
            else if (!isMedia(url) && url.startsWith("/")) url = resolve("fan_hot".equals(key) ? HOT : NOMI, url);
        } else if ("fan_bit".equals(key)) {
            url = resolveBitPlay(url);
        } else if ("fan_juquan".equals(key) && !isMedia(url)) {
            result.put("parse", 1);
            result.put("url", url);
            result.put("header", headerJson("User-Agent", "okhttp/3.14.9"));
            return result;
        } else if ("fan_jianpian".equals(key)) {
            url = resolveJianpianPlay(url);
        } else if ("fan_moli".equals(key)) {
            url = stripMoliMetadata(url);
            if (!isMedia(url)) {
                result.put("parse", 1);
                result.put("url", url);
                return result;
            }
        }

        if (TextUtils.isEmpty(url)) throw new IOException("播放地址为空");
        if (!isMedia(url)) {
            result.put("parse", 1);
            result.put("url", url);
        } else {
            result.put("url", url);
            result.put("header", "fan_hot".equals(key) ? hotHeader(url) : headerJson("User-Agent", UA));
        }
        return result;
    }

    private static String nomiHome() {
        JsonObject root = new JsonObject();
        addClass(root, "1", "电影");
        addClass(root, "2", "电视剧");
        addClass(root, "3", "综艺");
        addClass(root, "4", "动漫");
        addClass(root, "26", "短剧");
        return root.toString();
    }

    private static String bitHome() throws Exception {
        Document document = fetchDocument(BIT);
        JsonObject root = pageRoot(bitCards(document), 1, 1);
        addClass(root, "1", "电影");
        addClass(root, "2", "电视剧");
        addClass(root, "3", "动漫");
        return root.toString();
    }

    private static String juquanHome() throws Exception {
        JsonObject response = juquanPost("/api.php/getappapi.index/initV119", Collections.<String, String>emptyMap());
        JsonObject root = new JsonObject();
        JsonArray classes = array(response, "class", "classes", "type_list", "categories");
        if (classes.size() == 0) {
            addClass(root, "1", "电影");
            addClass(root, "2", "剧集");
            addClass(root, "3", "综艺");
        } else {
            root.add("class", classes);
        }
        return root.toString();
    }

    private static String nomiPage(Document document) {
        JsonArray list = new JsonArray();
        if (document != null) {
            for (Element link : document.select("a[href*='vod-detail']")) {
                String id = link.attr("href").trim();
                if (id.isEmpty()) continue;
                String name = firstNonEmpty(link.attr("title"), link.text());
                Element image = link.selectFirst("img");
                if (TextUtils.isEmpty(name) && image != null) name = image.attr("alt");
                JsonObject vod = vod(id, name, image == null ? "" : firstNonEmpty(image.attr("data-original"), image.attr("src")), "");
                if (!containsId(list, id)) list.add(vod);
            }
        }
        return page(list, 1, 1, list.size()).toString();
    }

    private static String bitPage(Document document) {
        JsonArray list = bitCards(document);
        return page(list, 1, 1, list.size()).toString();
    }

    private static JsonArray bitCards(Document document) {
        JsonArray list = new JsonArray();
        if (document == null) return list;
        for (Element link : document.select("a[href],a[data-href],a[data-url],[data-href],[data-url]")) {
            String id = cleanBitPath(firstNonEmpty(link.attr("href"), link.attr("data-href"), link.attr("data-url")));
            if (!id.matches("(?i).*(?:^|/)play/[^/?#]+.*")) continue;
            if (id.isEmpty() || containsId(list, id)) continue;
            Element image = link.selectFirst("img");
            Element card = link.closest("li,article,.item,.module,.video-item,.search-item");
            String name = firstNonEmpty(link.attr("title"), link.text(),
                    image == null ? "" : image.attr("alt"),
                    card == null ? "" : card.select("h1,h2,h3,h4,h5,.title,.name").text());
            list.add(vod(id, name, image == null ? "" : firstNonEmpty(image.attr("data-original"), image.attr("src")), ""));
        }
        return list;
    }

    private static String nomiDetail(String id) throws Exception {
        String detailUrl = resolve(NOMI, id);
        Document document = fetchDocument(detailUrl);
        JsonObject item = detailVod(document, id);
        JsonArray episodes = new JsonArray();
        if (document != null) {
            for (Element link : document.select("a[href*='vod-play']")) {
                String href = link.attr("href").trim();
                if (href.isEmpty()) continue;
                String name = firstNonEmpty(link.attr("title"), link.text());
                episodes.add((name.isEmpty() ? "播放" : name) + "$" + resolve(NOMI, href));
            }
        }
        if (episodes.size() == 0) episodes.add("播放$" + detailUrl);
        item.addProperty("vod_play_from", "线路1");
        item.addProperty("vod_play_url", joinJson(episodes));
        JsonArray list = new JsonArray(); list.add(item);
        return page(list, 1, 1, 1).toString();
    }

    private static String bitDetail(String id) throws Exception {
        String detailUrl = resolve(BIT, id);
        Document document = fetchDocument(detailUrl);
        JsonObject item = detailVod(document, id);
        JsonArray episodes = new JsonArray();
        if (document != null) {
            for (Element link : document.select("a[href*='play/']")) {
                String href = cleanBitPath(link.attr("href"));
                if (href.isEmpty()) continue;
                String name = firstNonEmpty(link.attr("title"), link.text());
                episodes.add((name.isEmpty() ? String.valueOf(episodes.size() + 1) : name)
                        + "${playUrl=" + resolve(BIT, href) + "}");
            }
        }
        if (episodes.size() == 0) episodes.add("播放$" + detailUrl);
        item.addProperty("vod_play_from", "播放");
        item.addProperty("vod_play_url", joinJson(episodes));
        JsonArray list = new JsonArray(); list.add(item);
        return page(list, 1, 1, 1).toString();
    }

    private static String juquanCategory(String id, int pageNumber, Map<String, String> filters) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("area", value(filters, "area", "全部"));
        form.put("year", value(filters, "year", "全部"));
        form.put("type_id", TextUtils.isEmpty(id) ? "1" : id);
        form.put("page", String.valueOf(Math.max(1, pageNumber)));
        form.put("sort", value(filters, "sort", "全部"));
        form.put("lang", value(filters, "lang", "全部"));
        form.put("class", value(filters, "class", "全部"));
        return normalizeApiPage(juquanPost("/api.php/getappapi.index/typeFilterVodList", form), pageNumber).toString();
    }

    private static String juquanSearch(String keyword) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("keywords", keyword);
        form.put("type_id", "0");
        form.put("page", "1");
        return normalizeApiPage(juquanPost("/api.php/getappapi.index/searchList", form), 1).toString();
    }

    private static String juquanDetail(String id) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("vod_id", id);
        JsonObject response = juquanPost("/api.php/getappapi.index/vodDetail", form);
        JsonObject item = firstVod(response, id);
        if (item == null) item = vod(id, id, "", "");
        JsonArray list = new JsonArray(); list.add(item);
        return page(list, 1, 1, 1).toString();
    }

    private static String cheeseHome() {
        JsonObject root = new JsonObject();
        addClass(root, "1", "电影");
        addClass(root, "2", "电视剧");
        addClass(root, "3", "综艺");
        addClass(root, "4", "动漫");
        return root.toString();
    }

    private static String cheeseSearch(String keyword) throws Exception {
        return normalizeApiPage(requestJson(CHEESE_API + "?ac=detail&wd=" + encode(keyword),
                Collections.<String, String>emptyMap()), 1).toString();
    }

    private static String cheesePage(String id, int pageNumber) throws Exception {
        String url = CHEESE_API + "?ac=list&t=" + encode(TextUtils.isEmpty(id) ? "1" : id)
                + "&pg=" + Math.max(1, pageNumber);
        return normalizeApiPage(requestJson(url, Collections.<String, String>emptyMap()), pageNumber).toString();
    }

    private static String cheeseDetail(String id) throws Exception {
        return normalizeApiPage(requestJson(CHEESE_API + "?ac=detail&ids=" + encode(id),
                Collections.<String, String>emptyMap()), 1).toString();
    }

    private static String jianpianHome() {
        JsonObject root = new JsonObject();
        addClass(root, "1", "电影");
        addClass(root, "2", "电视剧");
        addClass(root, "3", "综艺");
        addClass(root, "4", "动漫");
        return root.toString();
    }

    private static String jianpianCategory(String id, int pageNumber) {
        // 荐片公开接口的分类树和搜索接口是两套协议；分类页先返回标准空页，
        // 搜索、详情和播放走已确认的原生 API，不回退旧 JAR。
        return emptyPage().toString();
    }

    private static String jianpianSearch(String keyword) throws Exception {
        JsonObject source = JianpianApi.get().search(keyword, 1, 20);
        JsonArray list = new JsonArray();
        JsonArray raw = JianpianApi.array(source, "data");
        if (raw.size() == 0) raw = JianpianApi.array(source, "items");
        if (raw.size() == 0) raw = JianpianApi.array(source, "list");
        for (JsonElement element : raw) {
            JsonObject item = unwrapObject(element, "work", "vod");
            if (item == null) continue;
            JsonObject vod = JianpianAdapter.mapVod(item);
            if (!TextUtils.isEmpty(value(vod, "vod_id"))) list.add(vod);
        }
        int pageNumber = number(source, 1, "page");
        int total = number(source, list.size(), "found", "total", "count");
        return page(list, pageNumber, pageNumber, total).toString();
    }

    private static String jianpianDetail(String id) throws Exception {
        JsonObject vod = JianpianAdapter.mapDetail(JianpianApi.get().detail(id));
        JsonArray list = new JsonArray();
        list.add(vod);
        return page(list, 1, 1, 1).toString();
    }

    private static String jianpianPlayUrl(String workId, String episodeKey, String episodeIndex, String lineKey) {
        return "jianpian://play?work_id=" + encode(workId)
                + "&episode_key=" + encode(episodeKey)
                + "&episode_index=" + encode(episodeIndex)
                + "&line_key=" + encode(lineKey);
    }

    private static String resolveJianpianPlay(String raw) throws Exception {
        if (TextUtils.isEmpty(raw) || !raw.startsWith("jianpian://")) return raw;
        int queryStart = raw.indexOf('?');
        if (queryStart < 0) throw new IOException("荐片播放参数为空");
        HttpUrl query = HttpUrl.get("https://placeholder.invalid/?" + raw.substring(queryStart + 1));
        return JianpianApi.get().access(query.queryParameter("work_id"),
                query.queryParameter("episode_key"), query.queryParameter("episode_index"),
                query.queryParameter("line_key"));
    }

    private static JsonObject unwrapObject(JsonElement value, String... keys) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject()) return object.getAsJsonObject(key);
        }
        return object;
    }

    private static String firstJianpianEpisodeValue(JsonArray episodes, String... keys) {
        if (episodes == null || episodes.size() == 0) return "";
        return firstJianpianEpisodeValue(unwrapObject(episodes.get(0), "episode", "item"), keys);
    }

    private static String firstJianpianEpisodeValue(JsonObject episode, String... keys) {
        return episode == null ? "" : JianpianApi.findString(episode, keys);
    }

    private static boolean booleanValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        if (values != null) for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value == null ? "" : value);
        }
        return result.toString();
    }

    private static String appHome(String key) throws Exception {
        JsonObject response = appCall(key, "home", Collections.<String, String>emptyMap());
        JsonObject root = normalizeApiPage(response, 1);
        if (!root.has("class")) {
            addClass(root, "1", "电影");
            addClass(root, "2", "电视剧");
            addClass(root, "3", "综艺");
            addClass(root, "4", "动漫");
            addClass(root, "67", "短剧");
        }
        return root.toString();
    }

    private static String appPage(String key, String id, int pageNumber, Map<String, String> filters) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("category_id", TextUtils.isEmpty(id) ? "" : id);
        form.put("page", String.valueOf(Math.max(1, pageNumber)));
        form.put("limit", "20");
        if (filters != null) form.putAll(filters);
        return normalizeApiPage(appCall(key, "category", form), pageNumber).toString();
    }

    private static String appSearch(String key, String keyword) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("keyword", keyword);
        form.put("category_id", "88");
        form.put("page", "1");
        form.put("limit", "20");
        return normalizeApiPage(appCall(key, "search", form), 1).toString();
    }

    private static String appDetail(String key, String id) throws Exception {
        if ("fan_hot".equals(key)) return hotDetail(id);
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("vod_id", id);
        form.put("id", id);
        JsonObject response = appCall(key, "detail", form);
        JsonObject item = firstVod(response, id);
        if (item == null) item = vod(id, id, "", "");
        JsonArray list = new JsonArray(); list.add(item);
        return page(list, 1, 1, 1).toString();
    }

    private static String moliHome() {
        JsonObject root = new JsonObject();
        addClass(root, "20", "电影");
        addClass(root, "21", "剧集");
        addClass(root, "22", "动漫");
        addClass(root, "23", "综艺");
        addClass(root, "24", "短剧");
        addClass(root, "26", "动作片");
        addClass(root, "27", "喜剧片");
        addClass(root, "28", "爱情片");
        addClass(root, "29", "科幻片");
        addClass(root, "30", "恐怖片");
        addClass(root, "31", "剧情片");
        addClass(root, "32", "战争片");
        addClass(root, "33", "动画片");
        addClass(root, "34", "国产剧");
        addClass(root, "35", "港台剧");
        addClass(root, "36", "日韩剧");
        addClass(root, "37", "欧美剧");
        addClass(root, "38", "泰国剧");
        return root.toString();
    }

    private static String hotDetail(String id) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("vod_id", id);
        JsonObject response = appCall("fan_hot", "detail", form);
        JsonObject source = findObjectDeep(response, "data", "result", "vod", "item");
        if (source == null) source = response;
        JsonObject item = mapVod(source);
        if (TextUtils.isEmpty(value(item, "vod_id"))) item.addProperty("vod_id", id);
        if (TextUtils.isEmpty(value(item, "vod_name"))) item.addProperty("vod_name", id);

        JsonArray routes = findArrayDeep(source, "vip_source_list_source", "source_list_source",
                "vod_play_list", "play_list", "vodPlayList", "playList");
        List<String> from = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        for (JsonElement routeElement : routes) {
            if (!routeElement.isJsonObject()) continue;
            JsonObject route = routeElement.getAsJsonObject();
            String routeName = firstNonEmpty(value(route, "name", "title", "source_name"), "热播线路");
            JsonArray urls = array(route, "source_list", "urls", "episodes", "list", "items");
            List<String> episodes = new ArrayList<>();
            for (JsonElement episodeElement : urls) {
                JsonObject episode = episodeElement.isJsonObject() ? episodeElement.getAsJsonObject() : null;
                String url = episode == null ? primitive(episodeElement) : value(episode, "url", "play_url", "playUrl", "link");
                if (TextUtils.isEmpty(url)) continue;
                String title = episode == null ? "" : firstNonEmpty(value(episode, "name", "title", "episode", "sort"), "");
                episodes.add((title.isEmpty() ? String.valueOf(episodes.size() + 1) : title) + "$" + url);
            }
            String group = join(episodes, "#");
            if (!group.isEmpty() && seenGroups.add(group)) {
                from.add(routeName);
                groups.add(group);
            }
        }
        item.addProperty("vod_play_from", join(from, "$$$"));
        item.addProperty("vod_play_url", join(groups, "$$$"));
        JsonArray list = new JsonArray();
        list.add(item);
        return page(list, 1, 1, 1).toString();
    }

    private static String genericCmsSearch(String host, String keyword) throws Exception {
        JsonObject response = requestJson(host + "/api.php/provide/vod?ac=detail&wd=" + encode(keyword), Collections.<String, String>emptyMap());
        return normalizeApiPage(response, 1).toString();
    }

    private static String genericCmsPage(String host, String id, int pageNumber) throws Exception {
        String url = host + "/api.php/provide/vod?ac=list&t=" + encode(TextUtils.isEmpty(id) ? "1" : id) + "&pg=" + Math.max(1, pageNumber);
        return normalizeApiPage(requestJson(url, Collections.<String, String>emptyMap()), pageNumber).toString();
    }

    private static String genericCmsDetail(String host, String id) throws Exception {
        JsonObject response = requestJson(host + "/api.php/provide/vod?ac=detail&ids=" + encode(id), Collections.<String, String>emptyMap());
        return normalizeApiPage(response, 1).toString();
    }

    private static JSONObject resolveNbyResult(String value) throws Exception {
        return new JSONObject(requestText(NOMI_NBY + encode(value), Collections.singletonMap("User-Agent", "okhttp-okgo/jeasonlzy")));
    }

    private static String resolveNby(String value) throws Exception {
        JSONObject result = resolveNbyResult(value);
        String url = result.optString("url", result.optString("m3u8", ""));
        if (TextUtils.isEmpty(url)) throw new IOException("NBY 接口没有返回媒体地址");
        return url;
    }

    private static String resolveBitPlay(String raw) throws Exception {
        String url = extractBitPlayUrl(raw);
        if (TextUtils.isEmpty(url)) url = raw;
        List<String> candidates = new ArrayList<>();
        if (url.startsWith("http")) candidates.add(url);
        for (String base : BIT_BASES) {
            String candidate = resolve(base, url);
            if (!candidates.contains(candidate)) candidates.add(candidate);
        }
        Throwable last = null;
        for (String candidate : candidates) {
            try {
                String html = requestText(candidate, Collections.singletonMap("User-Agent", UA));
                String direct = findFirst(M3U8, unescapeHtml(html));
                if (!TextUtils.isEmpty(direct)) return direct;

                String key = findJsValue(html, "(?:aesKey|aes_key|key|KEY)");
                String iv = findJsValue(html, "(?:aesIv|aes_iv|iv|IV)");
                String cipherText = findCipherText(html);
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(iv) && !TextUtils.isEmpty(cipherText)) {
                    String plain = unescapeHtml(decryptAes(cipherText, key, iv));
                    direct = findFirst(M3U8, plain);
                    if (!TextUtils.isEmpty(direct)) return direct;
                    Matcher matcher = Pattern.compile("https?://[^\\\"'<>\\s]+", Pattern.CASE_INSENSITIVE).matcher(plain);
                    if (matcher.find()) return matcher.group();
                }
                last = new IOException("播放页未返回媒体地址");
            } catch (Throwable throwable) {
                last = throwable;
            }
        }
        throw new IOException("比特播放页未解析出媒体地址", last);
    }

    private static String stripMoliMetadata(String url) {
        if (url == null) return "";
        int at = url.indexOf('@');
        return at >= 0 ? url.substring(0, at) : url;
    }

    private static JsonObject appCall(String key, String action, Map<String, String> form) throws Exception {
        if ("home".equals(action)) {
            return hotGet("/api/crumb/list", mapOf("fcate_pid", "1", "area", "0", "year", "0",
                    "type", "0", "sort", "updata", "page", "1", "category_id", ""));
        }
        if ("category".equals(action)) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put("fcate_pid", "1");
            values.put("area", value(form, "area", "0"));
            values.put("year", value(form, "year", "0"));
            values.put("type", value(form, "type", "0"));
            values.put("sort", value(form, "sort", "updata"));
            values.put("page", value(form, "page", "1"));
            values.put("category_id", value(form, "category_id", value(form, "type_id", "")));
            return hotGet("/api/crumb/list", values);
        }
        if ("search".equals(action)) {
            return hotGet("/api/v2/search/videoV2", mapOf("key", value(form, "keyword", ""),
                    "category_id", value(form, "category_id", "88"), "page", value(form, "page", "1"),
                    "pageSize", value(form, "limit", "20")));
        }
        if ("detail".equals(action)) {
            return hotGet("/api/video/detailv2", mapOf("id", value(form, "id", value(form, "vod_id", ""))));
        }
        throw new IOException("未知热播接口动作: " + action);
    }

    private static JsonObject juquanPost(String path, Map<String, String> form) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("app-api-verify-time", String.valueOf(now));
        headers.put("app-api-verify-sign", sign(String.valueOf(now)));
        headers.put("app-ui-mode", "light");
        headers.put("app-user-device-id", "avbox-native-device");
        headers.put("app-version-code", "113");
        headers.put("User-Agent", "okhttp/3.14.9");
        JsonObject outer = postJson(JUQUAN + path, form, headers);
        String encrypted = value(outer, "data");
        if (!TextUtils.isEmpty(encrypted) && encrypted.length() > 32) {
            try {
                JsonElement plain = JsonParser.parseString(decryptAes(encrypted, JUQUAN_KEY, JUQUAN_KEY));
                if (plain.isJsonObject()) return plain.getAsJsonObject();
                if (plain.isJsonArray()) {
                    JsonObject wrapped = new JsonObject();
                    wrapped.add("list", plain);
                    wrapped.addProperty("total", plain.getAsJsonArray().size());
                    return wrapped;
                }
            } catch (Throwable ignored) {
                // 某些接口已经返回明文对象，只有字符串 data 才走外层解密。
            }
        }
        return outer;
    }

    private static String sign(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] key = JUQUAN_KEY.getBytes(StandardCharsets.UTF_8);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return android.util.Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }

    private static Map<String, String> appHeaders(String key) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        return headers;
    }

    private static JsonObject normalizeApiPage(JsonObject source, int pageNumber) {
        if (source == null) return page(new JsonArray(), pageNumber, 1, 0);
        JsonArray raw = findArrayDeep(source, "list", "items", "results", "vod_list", "videos", "data");
        JsonArray list = new JsonArray();
        for (JsonElement element : raw) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject mapped = mapVod(item);
            if (!TextUtils.isEmpty(value(mapped, "vod_id"))) list.add(mapped);
        }
        JsonObject candidate = findObjectDeep(source, "vod", "item", "video");
        if (candidate == null) candidate = source;
        if (list.size() == 0 && (candidate.has("vod_id") || candidate.has("id"))) list.add(mapVod(candidate));
        JsonObject root = page(list, pageNumber,
                number(candidate, 1, "pagecount", "page_count", "pages", "pageCount"),
                number(candidate, list.size(), "total", "count"));
        if (source.has("class")) root.add("class", source.get("class"));
        return root;
    }

    private static JsonObject mapVod(JsonObject source) {
        JsonObject target = new JsonObject();
        copy(target, "vod_id", source, "vod_id", "id", "vodId", "video_id");
        copy(target, "vod_name", source, "vod_name", "name", "title", "vodName");
        copy(target, "vod_pic", source, "vod_pic", "pic", "cover", "poster", "vodPic", "thumbnail", "tvimg", "path");
        copy(target, "vod_remarks", source, "vod_remarks", "remarks", "note", "vodRemarks", "mask");
        copy(target, "vod_year", source, "vod_year", "year");
        copy(target, "vod_area", source, "vod_area", "area");
        copy(target, "vod_actor", source, "vod_actor", "actor", "actors");
        copy(target, "vod_director", source, "vod_director", "director");
        copy(target, "vod_content", source, "vod_content", "content", "desc", "description", "vodContent");
        copy(target, "vod_class", source, "vod_class", "class", "type_name", "category");
        copy(target, "vod_score", source, "vod_score", "score");
        copy(target, "vod_play_from", source, "vod_play_from", "play_from", "playFrom", "source");
        copy(target, "vod_play_url", source, "vod_play_url", "play_url", "playUrl", "url");
        normalizeImage(target, "vod_pic");
        return target;
    }

    private static JsonObject firstVod(JsonObject source, String fallbackId) {
        JsonObject page = normalizeApiPage(source, 1);
        if (page.has("list") && page.getAsJsonArray("list").size() > 0) return page.getAsJsonArray("list").get(0).getAsJsonObject();
        return vod(fallbackId, fallbackId, "", "");
    }

    private static JsonObject detailVod(Document document, String id) {
        String name = document == null ? id : firstNonEmpty(document.select("h1").text(), document.title());
        String image = document == null ? "" : firstImage(document);
        String text = document == null ? "" : document.text();
        return vod(id, name, image, text);
    }

    private static JsonObject page(JsonArray list, int pageNumber, int pageCount, int total) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", Math.max(1, pageNumber));
        root.addProperty("pagecount", Math.max(1, pageCount));
        root.addProperty("limit", 20);
        root.addProperty("total", Math.max(total, list.size()));
        root.add("list", list == null ? new JsonArray() : list);
        return root;
    }

    private static JsonObject vod(String id, String name, String pic, String content) {
        JsonObject item = new JsonObject();
        item.addProperty("vod_id", id == null ? "" : id);
        item.addProperty("vod_name", TextUtils.isEmpty(name) ? id : name);
        item.addProperty("vod_pic", pic == null ? "" : pic);
        item.addProperty("vod_content", content == null ? "" : content);
        return item;
    }

    private static JsonObject emptyPage() { return page(new JsonArray(), 1, 1, 0); }

    private static void addClass(JsonObject root, String id, String name) {
        JsonArray classes = root.has("class") ? root.getAsJsonArray("class") : new JsonArray();
        JsonObject item = new JsonObject(); item.addProperty("type_id", id); item.addProperty("type_name", name); classes.add(item); root.add("class", classes);
    }

    private static JsonObject requestJson(String url, Map<String, String> params, Map<String, String> headers) throws Exception {
        String target = url;
        if (params != null && !params.isEmpty() && !url.contains("?")) {
            HttpUrl.Builder builder = HttpUrl.get(url).newBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
            target = builder.build().toString();
        }
        String raw = requestText(target, headers);
        JsonElement parsed = JsonParser.parseString(raw);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject requestJson(String url, Map<String, String> params) throws Exception { return requestJson(url, params, Collections.<String, String>emptyMap()); }

    /** 热播站点域名会通过 TXT 轮换;每次请求只探测一次并保留主域名兜底。 */
    private static JsonObject hotGet(String path, Map<String, String> params) throws Exception {
        Throwable last = null;
        for (String base : hotBases()) {
            try {
                HttpUrl.Builder builder = HttpUrl.get(base + path).newBuilder();
                if (params != null) {
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (entry.getValue() != null) builder.addQueryParameter(entry.getKey(), entry.getValue());
                    }
                }
                String url = builder.build().toString();
                String raw = requestText(url, hotRequestHeaders(base));
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Throwable throwable) {
                last = throwable;
            }
        }
        throw new IOException("热播接口请求失败", last);
    }

    private static List<String> hotBases() {
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        values.put(HOT, true);
        try {
            JsonElement answer = JsonParser.parseString(requestText(HOT_DOMAIN_DNS,
                    Collections.singletonMap("User-Agent", UA)));
            if (answer.isJsonObject() && answer.getAsJsonObject().has("Answer")) {
                for (JsonElement element : answer.getAsJsonObject().getAsJsonArray("Answer")) {
                    String data = value(element.getAsJsonObject(), "data");
                    for (String candidate : data.replace("\"", "").split(",")) {
                        String host = candidate.trim();
                        if (host.isEmpty()) continue;
                        if (!host.startsWith("http")) host = "https://" +
                                (host.startsWith("fan123.") ? host : "fan123." + host);
                        values.put(host, true);
                    }
                }
            }
        } catch (Throwable ignored) {
            // TXT 解析失败时继续使用已确认的主域名。
        }
        return new ArrayList<>(values.keySet());
    }

    private static Map<String, String> hotRequestHeaders(String base) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", base == null || base.isEmpty() ? HOT_REFERER : base);
        headers.put("Accept", "application/json,text/plain,*/*");
        return headers;
    }

    private static String hotHeader(String url) {
        JsonObject header = new JsonObject();
        header.addProperty("User-Agent", UA);
        header.addProperty("Referer", HOT_REFERER);
        return header.toString();
    }

    private static JsonObject postJson(String url, Map<String, String> form, Map<String, String> headers) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        if (form != null) for (Map.Entry<String, String> entry : form.entrySet()) body.add(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        Request.Builder builder = new Request.Builder().url(url).post(body.build());
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        try (Response response = OkHttp.client(20000).newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            String raw = response.body().string();
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
    }

    private static String requestText(String url, Map<String, String> headers) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).get();
        builder.header("User-Agent", UA);
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        try (Response response = OkHttp.client(20000).newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return response.body().string();
        }
    }

    private static Document fetchDocument(String url) throws Exception { return Jsoup.parse(requestText(url, Collections.singletonMap("Referer", url)), url); }

    private static Map<String, String> mapOf(String... values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (values != null) {
            for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private static JsonArray array(JsonObject object, String... keys) {
        if (object == null) return new JsonArray();
        for (String key : keys) if (object.has(key) && object.get(key).isJsonArray()) return object.getAsJsonArray(key);
        return new JsonArray();
    }

    private static JsonArray findArrayDeep(JsonElement element, String... keys) {
        if (element == null || element.isJsonNull()) return new JsonArray();
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonObject()) return new JsonArray();
        JsonObject object = element.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonArray()) return object.getAsJsonArray(key);
        }
        for (String key : new String[]{"data", "result", "vod", "item", "response"}) {
            if (object.has(key)) {
                JsonArray nested = findArrayDeep(object.get(key), keys);
                if (nested.size() > 0) return nested;
            }
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if ("class".equals(entry.getKey()) || "filters".equals(entry.getKey())) continue;
            JsonArray nested = findArrayDeep(entry.getValue(), keys);
            if (nested.size() > 0) return nested;
        }
        return new JsonArray();
    }

    private static JsonObject findObjectDeep(JsonElement element, String... keys) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject()) return object.getAsJsonObject(key);
        }
        for (String key : new String[]{"data", "result", "vod", "item", "response"}) {
            if (object.has(key)) {
                JsonObject nested = findObjectDeep(object.get(key), keys);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static int number(JsonObject object, String... keys) {
        for (String key : keys) try { if (object != null && object.has(key)) return object.get(key).getAsInt(); } catch (Throwable ignored) { }
        return 1;
    }

    private static int number(JsonObject object, int fallback, String... keys) {
        for (String key : keys) try { if (object != null && object.has(key)) return object.get(key).getAsInt(); } catch (Throwable ignored) { }
        return fallback;
    }

    private static void copy(JsonObject target, String key, JsonObject source, String... aliases) {
        String value = value(source, aliases);
        if (!TextUtils.isEmpty(value)) target.addProperty(key, value);
    }

    private static void normalizeImage(JsonObject target, String key) {
        if (target == null || !target.has(key)) return;
        String image = value(target, key);
        if (image.startsWith("//")) image = "https:" + image;
        else if (image.startsWith("/")) image = HOT + image;
        if (!image.isEmpty()) target.addProperty(key, image);
    }

    private static String value(JsonObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) try { if (object.has(key) && !object.get(key).isJsonNull()) { String value = object.get(key).getAsString().trim(); if (!value.isEmpty()) return value; } } catch (Throwable ignored) { }
        return "";
    }

    private static String primitive(JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String value(Map<String, String> values, String key, String fallback) { return values != null && values.get(key) != null && !values.get(key).trim().isEmpty() ? values.get(key).trim() : fallback; }

    private static String firstImage(Document document) { Element image = document.selectFirst("meta[property=og:image]"); if (image != null) return image.attr("content"); image = document.selectFirst("img[src]"); return image == null ? "" : image.attr("src"); }

    private static String firstNonEmpty(String... values) { if (values != null) for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim(); return ""; }

    private static boolean containsId(JsonArray list, String id) { for (JsonElement element : list) if (element.isJsonObject() && id.equals(value(element.getAsJsonObject(), "vod_id"))) return true; return false; }

    private static String joinJson(JsonArray values) { StringBuilder result = new StringBuilder(); for (JsonElement value : values) { if (result.length() > 0) result.append('#'); result.append(value.getAsString()); } return result.toString(); }

    private static String encode(String value) { try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); } catch (Exception ignored) { return value == null ? "" : value; } }

    private static String resolve(String base, String path) { if (path == null || path.isEmpty()) return base; try { if (path.startsWith("http://") || path.startsWith("https://")) return path; return HttpUrl.get(base).resolve(path).toString(); } catch (Throwable ignored) { return path; } }

    private static String cleanBitPath(String value) {
        if (value == null) return "";
        String text = Entities.unescape(value).trim();
        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);
        return text;
    }

    private static String extractBitPlayUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        Matcher matcher = Pattern.compile("(?is)(?:playUrl|play_url|play-url|url|href|data-url)\\s*\\\"?\\s*[=:]\\s*[\\\"']?([^,}\\s\\\"']+)").matcher(raw);
        if (matcher.find()) return cleanBitPath(matcher.group(1));
        matcher = Pattern.compile("(?is)\\$\\{[^}]*?playUrl\\s*=\\s*([^,}\\s]+)").matcher(raw);
        if (matcher.find()) return cleanBitPath(matcher.group(1));
        return cleanBitPath(raw);
    }

    private static boolean isMedia(String url) { if (TextUtils.isEmpty(url)) return false; String lower = url.toLowerCase(Locale.ROOT); return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mpd") || lower.contains(".flv"); }
    private static boolean isNby(String value) { return value != null && NBY.matcher(value).find(); }
    private static String findFirst(Pattern pattern, String value) { if (value == null) return ""; Matcher matcher = pattern.matcher(value); return matcher.find() ? matcher.group() : ""; }
    private static String findJsValue(String html, String name) {
        String source = html == null ? "" : html;
        Matcher matcher = Pattern.compile("(?is)\\\"?(?:" + name + ")\\\"?\\s*(?:[:=]|\\()\\s*[\\\"']([^\\\"']+)").matcher(source);
        if (matcher.find()) return Entities.unescape(matcher.group(1)).trim();
        return "";
    }

    private static String findCipherText(String html) {
        String source = html == null ? "" : html;
        Matcher matcher = Pattern.compile("(?is)(?:data|cipher|content|encrypt(?:ed)?|result|value)\\s*[:=]\\s*[\\\"']([A-Za-z0-9+/=_-]{32,})").matcher(source);
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("[\\\"']([A-Za-z0-9+/=_-]{80,})[\\\"']").matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unescapeHtml(String value) {
        return Entities.unescape(value == null ? "" : value);
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static String decryptAes(String cipherText, String key, String iv) throws Exception {
        String value = cipherText == null ? "" : cipherText.trim();
        byte[] data;
        if (value.matches("(?i)[0-9a-f]{32,}") && (value.length() & 1) == 0) {
            data = new byte[value.length() / 2];
            for (int i = 0; i < value.length(); i += 2) {
                data[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
            }
        } else {
            data = android.util.Base64.decode(value, android.util.Base64.DEFAULT | android.util.Base64.URL_SAFE);
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(pad16(key).getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(pad16(iv).getBytes(StandardCharsets.UTF_8)));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    private static String pad16(String value) { String text = value == null ? "" : value; if (text.length() >= 16) return text.substring(0, 16); StringBuilder result = new StringBuilder(text); while (result.length() < 16) result.append('0'); return result.toString(); }

    private static String headerJson(String key, String value) { JsonObject header = new JsonObject(); header.addProperty(key, value); return header.toString(); }

    private static JsonObject pageRoot(JsonArray list, int pageNumber, int total) {
        return page(list, pageNumber, 1, total);
    }
}
