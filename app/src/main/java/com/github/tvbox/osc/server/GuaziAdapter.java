package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;

/** 把瓜子影视的加密接口转换为 TVBox 标准 CMS JSON。 */
final class GuaziAdapter implements CmsAdapter {
    private static final Map<String, JsonObject> SEARCH_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, HomePlaylist> HOME_PLAYLISTS = new ConcurrentHashMap<>();
    private static volatile List<MovieCategory> HOME_SORTS = Collections.emptyList();

    private static final String[] CATEGORY_ORDER = {"2", "1", "64", "3", "4"};
    private static final String[] CATEGORY_NAMES = {"电视剧", "电影", "短剧", "综艺", "动漫"};
    private static final String[] PRIORITY_HOME_TITLES = {"9月热榜新剧", "最新热播电影"};
    private static final int MAX_HOME_SORTS = 15;
    private static final String[] HOME_CONTAINER_KEYS = {"list", "data", "result", "payload"};

    @Override
    public String id() {
        return "guazi";
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                         String fileName,
                                         Map<String, String> params,
                                         Map<String, String> files) {
        try {
            String ids = value(params, "ids");
            String keyword = value(params, "wd");
            if (!ids.isEmpty()) return jsonResponse(detail(ids.split(",", 2)[0].trim()));
            if (!keyword.isEmpty()) return jsonResponse(search(keyword));
            String type = value(params, "t");
            if (!type.isEmpty()) return jsonResponse(category(type, number(params, "pg", 1), params));
            return jsonResponse(home());
        } catch (Throwable throwable) {
            return jsonResponse(error("瓜子影视适配失败: " + safeMessage(throwable)));
        }
    }

    private String home() throws Exception {
        List<MovieCategory> sorts = loadHomeSorts();
        JsonObject root = new JsonObject();
        JsonArray classes = new JsonArray();
        for (MovieCategory sort : sorts) {
            JsonObject item = new JsonObject();
            item.addProperty("type_id", sort.id);
            item.addProperty("type_name", sort.name);
            classes.add(item);
        }
        root.add("class", classes);

        JsonArray list = new JsonArray();
        if (!sorts.isEmpty()) {
            JsonObject first = categoryObject(sorts.get(0), 1, Collections.<String, String>emptyMap());
            list = array(first, "list");
        }
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", 30);
        root.addProperty("total", list.size());
        root.add("list", list);
        return root.toString();
    }

    private String category(String id, int page, Map<String, String> filters) throws Exception {
        List<MovieCategory> sorts = loadHomeSorts();
        MovieCategory sort = null;
        for (MovieCategory candidate : sorts) {
            if (candidate.id.equals(id)) {
                sort = candidate;
                break;
            }
        }
        if (sort == null) sort = new MovieCategory(id, id);
        return categoryObject(sort, page, filters).toString();
    }

    private JsonObject categoryObject(MovieCategory sort, int page, Map<String, String> filters) throws Exception {
        JsonObject response;
        HomePlaylist playlist = HOME_PLAYLISTS.get(sort.id);
        if (playlist != null) {
            if (page > 1) return pageObject(new JsonArray(), page, 1, 0);
            response = playlist.showId.isEmpty()
                    ? objectWithList(playlist.embedded)
                    : GuaziApi.get().homePlaylist(playlist.showId, playlist.pid);
        } else {
            response = GuaziApi.get().category(sort.id, page, filters);
        }
        JsonArray raw = firstArray(response, "list", "data", "result", "payload");
        JsonArray list = new JsonArray();
        for (JsonElement element : raw) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = mapVod(element.getAsJsonObject());
            if (!string(vod, "vod_id").isEmpty()) list.add(vod);
        }
        int total = number(response, "total", list.size());
        int pages = number(response, "pagecount", Math.max(1, (total + 29) / 30));
        return pageObject(list, page, pages, total);
    }

    /**
     * 瓜子首页的导航是“页面 -> 片单”的两级结构。只探测首批导航页和
     * iOS 端固定优先片单所在的 pid，避免启动时把整个首页目录全部拉完。
     */
    private static synchronized List<MovieCategory> loadHomeSorts() throws Exception {
        if (!HOME_SORTS.isEmpty()) return HOME_SORTS;

        LinkedHashMap<String, MovieCategory> categories = new LinkedHashMap<>();
        for (int index = 0; index < CATEGORY_ORDER.length; index++) {
            categories.put(CATEGORY_ORDER[index], new MovieCategory(
                    CATEGORY_ORDER[index], CATEGORY_NAMES[index], false));
        }

        JsonArray navigation = GuaziApi.get().homeNavigation();
        List<String> pageIds = new ArrayList<>();
        java.util.HashSet<String> seenPageIds = new java.util.HashSet<>();
        for (JsonElement element : navigation) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String type = firstString(item, "type", "p_type", "kind");
            String pid = firstString(item, "pid", "id", "page_id");
            if (pid.isEmpty() || "0".equals(pid)) continue;
            if (!type.isEmpty() && !"video".equalsIgnoreCase(type)
                    && !"recommend".equalsIgnoreCase(type)
                    && !"2".equals(type)) continue;
            if (seenPageIds.add(pid)) pageIds.add(pid);
        }

        LinkedHashMap<String, HomePlaylist> playlists = new LinkedHashMap<>();
        // 首页标签顺序必须跟随当前接口返回的片单顺序。只读取导航页，不再依赖上一版固定 pid，
        // 这样接口更新后“9月热榜新剧/最新热播电影”仍能被放到正确位置。
        for (String pid : pageIds) {
            try {
                JsonObject page = GuaziApi.get().homePage(pid);
                collectPlaylists(page, pid, playlists);
            } catch (Throwable ignored) {
                // 单个首页栏目失效不应阻断其余栏目和普通分类。
            }
        }

        List<MovieCategory> ordered = new ArrayList<>();
        java.util.HashSet<String> added = new java.util.HashSet<>();
        for (String title : PRIORITY_HOME_TITLES) {
            HomePlaylist playlist = findPlaylistByTitle(playlists.values(), title);
            if (playlist != null && added.add(playlist.category.id)) ordered.add(playlist.category);
            if (ordered.size() >= MAX_HOME_SORTS) break;
        }
        for (HomePlaylist playlist : playlists.values()) {
            if (added.add(playlist.category.id)) ordered.add(playlist.category);
            if (ordered.size() >= MAX_HOME_SORTS) break;
        }
        for (String id : CATEGORY_ORDER) {
            MovieCategory category = categories.get(id);
            if (category != null && added.add(id)) ordered.add(category);
            if (ordered.size() >= MAX_HOME_SORTS) break;
        }

        HOME_PLAYLISTS.clear();
        for (HomePlaylist playlist : playlists.values()) {
            if (added.contains(playlist.category.id)) {
                HOME_PLAYLISTS.put(playlist.category.id, playlist);
            }
        }
        HOME_SORTS = Collections.unmodifiableList(new ArrayList<>(ordered));
        return HOME_SORTS;
    }

    private static HomePlaylist findPlaylistByTitle(Iterable<HomePlaylist> playlists, String expectedTitle) {
        if (playlists == null || TextUtils.isEmpty(expectedTitle)) return null;
        for (HomePlaylist playlist : playlists) {
            if (playlist == null || playlist.category == null) continue;
            String actual = normalizeHomeTitle(playlist.category.name);
            String expected = normalizeHomeTitle(expectedTitle);
            if (actual.equals(expected) || actual.contains(expected) || expected.contains(actual)) return playlist;
        }
        return null;
    }

    static List<String> orderHomeTitlesForTest(List<String> titles) {
        List<String> source = titles == null ? Collections.<String>emptyList() : titles;
        List<String> result = new ArrayList<>();
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        for (String expected : PRIORITY_HOME_TITLES) {
            for (int i = 0; i < source.size(); i++) {
                if (used.contains(i)) continue;
                String actual = normalizeHomeTitle(source.get(i));
                String wanted = normalizeHomeTitle(expected);
                if (actual.equals(wanted) || actual.contains(wanted) || wanted.contains(actual)) {
                    result.add(source.get(i));
                    used.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < source.size() && result.size() < MAX_HOME_SORTS; i++) {
            if (used.add(i)) result.add(source.get(i));
        }
        return result;
    }

    private static String normalizeHomeTitle(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").trim();
    }

    private static void collectPlaylists(JsonObject page, String fallbackPid,
                                         Map<String, HomePlaylist> target) {
        JsonArray items = firstArray(page, HOME_CONTAINER_KEYS);
        int index = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String playlistType = firstString(item, "p_type", "type_id", "item_type");
            String title = firstString(item, "name", "show_name", "title", "type");
            String pid = firstString(item, "pid", "type_id", "parent_id");
            if (pid.isEmpty()) pid = fallbackPid;
            String showId = firstString(item, "show_id", "showId", "id");
            JsonArray embedded = firstArray(item, HOME_CONTAINER_KEYS);
            if (!"2".equals(playlistType) || title.isEmpty() || pid.isEmpty()
                    || (showId.isEmpty() && embedded.size() == 0)) {
                index++;
                continue;
            }
            String suffix = showId.isEmpty() ? "embedded-" + index : showId;
            String id = "guazi-playlist:" + pid + ":" + suffix;
            if ("guazi-playlist:16:embedded-0".equals(id)) {
                index++;
                continue;
            }
            MovieCategory category = new MovieCategory(id,
                    "guazi-playlist:3:19260".equals(id) ? "TC抢先看" : title, true);
            target.putIfAbsent(id, new HomePlaylist(category, showId, pid, embedded));
            index++;
        }
    }

    private String search(String keyword) throws Exception {
        JsonObject source = GuaziApi.get().search(keyword);
        JsonArray list = new JsonArray();
        JsonArray sourceList = array(source, "list");
        for (JsonElement element : sourceList) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = mapVod(element.getAsJsonObject());
            if (!matchesSearchTitle(keyword, string(vod, "vod_name"))) continue;
            String id = string(vod, "vod_id");
            if (id.isEmpty()) continue;
            SEARCH_CACHE.put(id, vod.deepCopy());
            list.add(vod);
        }
        return page(list, 1, 1);
    }

    static boolean matchesSearchTitle(String keyword, String title) {
        String normalizedKeyword = normalizeSearchText(keyword);
        return !normalizedKeyword.isEmpty()
                && normalizeSearchText(title).contains(normalizedKeyword);
    }

    private static String normalizeSearchText(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
        });
        return result.toString();
    }

    private String detail(String id) throws Exception {
        JsonObject metadata = SEARCH_CACHE.get(id);
        JsonObject source = GuaziApi.get().detail(id);
        JsonObject vod = mergeVod(metadata, source);
        if (string(vod, "vod_content").isEmpty()) {
            try {
                vod = mergeVod(vod, GuaziApi.get().playInfo(id));
            } catch (Throwable ignored) {
                // 播放信息不是详情必需项,简介仍保留搜索/详情字段。
            }
        }
        vod.addProperty("vod_id", id);
        if (string(vod, "vod_name").isEmpty()) vod.addProperty("vod_name", "瓜子影视 " + id);

        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        JsonArray clouds = array(source, "vurl_clouds");
        for (JsonElement cloudElement : clouds) {
            if (!cloudElement.isJsonObject()) continue;
            JsonObject cloud = cloudElement.getAsJsonObject();
            String cloudId = string(cloud, "id");
            if (cloudId.isEmpty()) continue;
            JsonObject episodes = GuaziApi.get().episodes(cloudId, id);
            String group = buildEpisodeUrls(id, cloudId, episodes);
            if (group.isEmpty()) continue;
            if (from.length() > 0) {
                from.append("$$$");
                urls.append("$$$");
            }
            String name = string(cloud, "name");
            from.append(name.isEmpty() ? "瓜子线路" : name);
            urls.append(group);
        }
        vod.addProperty("vod_play_from", from.toString());
        vod.addProperty("vod_play_url", urls.toString());
        JsonArray list = new JsonArray();
        list.add(vod);
        return page(list, 1, 1);
    }

    private String buildEpisodeUrls(String vodId, String cloudId, JsonObject episodes) {
        JsonArray list = array(episodes, "list");
        List<String> values = new ArrayList<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject episode = element.getAsJsonObject();
            String vurlId = string(episode, "id");
            String title = string(episode, "title");
            String param = choosePlayParam(episode);
            if (vurlId.isEmpty() || param.isEmpty()) continue;
            Map<String, String> query = parseQuery(param);
            String domainType = query.get("domain_type");
            String resolution = query.get("resolution");
            String type = query.get("type");
            if (TextUtils.isEmpty(domainType) || TextUtils.isEmpty(resolution)) continue;
            try {
                HttpUrl url = HttpUrl.get("http://127.0.0.1:" + RemoteServer.serverPort
                                + "/guazi/play.m3u8")
                        .newBuilder()
                        .addQueryParameter("vod_id", vodId)
                        .addQueryParameter("vurl_cloud_id", cloudId)
                        .addQueryParameter("vurl_id", vurlId)
                        .addQueryParameter("domain_type", domainType)
                        .addQueryParameter("resolution", resolution)
                        .addQueryParameter("type", TextUtils.isEmpty(type) ? "play" : type)
                        .build();
                values.add((title.isEmpty() ? String.valueOf(values.size() + 1) : title) + "$" + url);
            } catch (Throwable ignored) {
            }
        }
        return join(values, "#");
    }

    private String choosePlayParam(JsonObject episode) {
        JsonObject play = episode != null && episode.has("play") && episode.get("play").isJsonObject()
                ? episode.getAsJsonObject("play") : null;
        if (play == null) return "";
        String[] resolutions = {"720", "1080", "480"};
        for (String resolution : resolutions) {
            if (!play.has(resolution) || !play.get(resolution).isJsonObject()) continue;
            String param = string(play.getAsJsonObject(resolution), "param");
            if (!param.isEmpty()) return param;
        }
        return "";
    }

    private JsonObject mapVod(JsonObject source) {
        JsonObject vod = new JsonObject();
        copy(vod, "vod_id", source, "vod_id");
        copy(vod, "vod_name", source, "vod_name", "d_name", "c_name", "title");
        copy(vod, "vod_pic", source, "vod_pic", "d_pic", "c_pic", "pic");
        copy(vod, "vod_year", source, "vod_year", "d_year");
        copy(vod, "vod_area", source, "vod_area", "d_area");
        copy(vod, "vod_actor", source, "vod_actor", "d_actor");
        copy(vod, "vod_director", source, "vod_director", "vod_directed");
        copy(vod, "vod_class", source, "vod_class", "d_type", "d_class", "type_name");
        copy(vod, "vod_score", source, "vod_scroe", "vod_score", "vod_douban_score");
        copy(vod, "vod_remarks", source, "new_continue", "vod_remarks", "vod_title", "cf_name", "sub_title");
        copy(vod, "vod_content", source, "vod_use_content", "d_content", "d_blurb", "d_desc",
                "d_description", "d_intro", "d_synopsis", "d_summary", "vod_content", "vod_blurb",
                "vod_desc", "vod_description", "vod_intro", "vod_summary", "synopsis", "summary",
                "intro", "introduction", "description", "desc", "content");
        cleanDescription(vod, "vod_content");
        vod.addProperty("vod_play_from", "");
        vod.addProperty("vod_play_url", "");
        return vod;
    }

    private JsonObject mergeVod(JsonObject first, JsonObject second) {
        JsonObject result = first == null ? new JsonObject() : mapVod(first);
        if (second == null) return result;
        JsonObject extra = mapVod(second);
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) continue;
            if (string(result, entry.getKey()).isEmpty() || "vod_play_from".equals(entry.getKey())
                    || "vod_play_url".equals(entry.getKey())) {
                result.add(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static void cleanDescription(JsonObject object, String key) {
        String value = string(object, key);
        if (value.isEmpty()) return;
        value = value.replaceAll("(?i)<br\\s*/?>", "\\n")
                .replaceAll("(?i)</p>", "\\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
        object.addProperty(key, value);
    }

    private static void copy(JsonObject target, String targetKey, JsonObject source, String... sourceKeys) {
        if (source == null || sourceKeys == null) return;
        for (String sourceKey : sourceKeys) {
            String value = firstStringDeep(source, sourceKey);
            if (!value.isEmpty()) {
                target.addProperty(targetKey, value);
                return;
            }
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonArray firstArray(JsonObject object, String... keys) {
        if (object == null || keys == null) return new JsonArray();
        for (String key : keys) {
            JsonArray direct = array(object, key);
            if (direct.size() > 0) return direct;
        }
        for (String key : keys) {
            if (!object.has(key) || !object.get(key).isJsonObject()) continue;
            JsonArray nested = firstArray(object.getAsJsonObject(key), keys);
            if (nested.size() > 0) return nested;
        }
        return new JsonArray();
    }

    private static JsonObject objectWithList(JsonArray list) {
        JsonObject object = new JsonObject();
        object.add("list", list == null ? new JsonArray() : list);
        return object;
    }

    private static int number(Map<String, String> params, String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value(params, key)));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int number(JsonObject object, String key, int fallback) {
        try {
            String value = string(object, key);
            return value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String firstString(JsonObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String firstStringDeep(JsonObject object, String key) {
        String direct = string(object, key);
        if (!direct.isEmpty()) return direct;
        for (String container : new String[]{"detail", "data", "result", "payload", "vod", "info", "item"}) {
            if (!object.has(container) || !object.get(container).isJsonObject()) continue;
            String nested = firstStringDeep(object.getAsJsonObject(container), key);
            if (!nested.isEmpty()) return nested;
        }
        return "";
    }

    private static Map<String, String> parseQuery(String value) {
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : value.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) result.put(pair.substring(0, index), pair.substring(index + 1));
        }
        return result;
    }

    private static JsonObject pageObject(JsonArray list, int page, int pageCount, int total) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", page);
        root.addProperty("pagecount", pageCount);
        root.addProperty("limit", 20);
        root.addProperty("total", Math.max(total, list.size()));
        root.add("list", list);
        return root;
    }

    private static String page(JsonArray list, int page, int pageCount) {
        return pageObject(list, page, pageCount, list.size()).toString();
    }

    private static String page(JsonArray list, int page, int pageCount, int total) {
        return pageObject(list, page, pageCount, total).toString();
    }

    private static String emptyPage(String message) {
        return page(new JsonArray(), 1, 1).replace("\"code\":0", "\"code\":0,\"msg\":\"" + message + "\"");
    }

    private static String error(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", 20);
        root.addProperty("total", 0);
        root.add("list", new JsonArray());
        return root.toString();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message;
    }

    private static NanoHTTPD.Response jsonResponse(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static final class MovieCategory {
        final String id;
        final String name;
        final boolean playlist;

        MovieCategory(String id, String name) {
            this(id, name, false);
        }

        MovieCategory(String id, String name, boolean playlist) {
            this.id = id == null ? "" : id;
            this.name = name == null || name.trim().isEmpty() ? this.id : name.trim();
            this.playlist = playlist;
        }
    }

    private static final class HomePlaylist {
        final MovieCategory category;
        final String showId;
        final String pid;
        final JsonArray embedded;

        HomePlaylist(MovieCategory category, String showId, String pid, JsonArray embedded) {
            this.category = category;
            this.showId = showId == null ? "" : showId;
            this.pid = pid == null ? "" : pid;
            this.embedded = embedded == null ? new JsonArray() : embedded;
        }
    }
}
