package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** 把荐片 v2 搜索和详情直接转换为 TVBox 标准 CMS JSON。 */
final class JianpianAdapter implements CmsAdapter {
    private static final String IMAGE_ORIGIN = "https://img.ypfbj.com";

    @Override
    public String id() {
        return CmsAdapterRouter.JIANPIAN;
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
            return jsonResponse(emptyPage("荐片"));
        } catch (Throwable throwable) {
            return jsonResponse(error("荐片适配失败: " + safeMessage(throwable)));
        }
    }

    private String search(String keyword) throws Exception {
        JsonObject source = JianpianApi.get().search(keyword, 1, 20);
        JsonArray raw = JianpianApi.array(source, "data");
        if (raw.size() == 0) raw = JianpianApi.array(source, "items");
        if (raw.size() == 0) raw = JianpianApi.array(source, "list");
        JsonArray list = new JsonArray();
        for (JsonElement element : raw) {
            JsonObject item = unwrap(element, "work", "vod");
            if (item == null) continue;
            JsonObject vod = mapVod(item);
            if (!string(vod, "vod_id").isEmpty()) list.add(vod);
        }
        return page(list, intValue(source, "page", 1), 1,
                intValue(source, "total", list.size())).toString();
    }

    private String detail(String id) throws Exception {
        return page(single(mapDetail(JianpianApi.get().detail(id))), 1, 1, 1).toString();
    }

    static JsonObject mapDetail(JsonObject source) {
        JsonObject vod = mapVod(source);
        if (string(vod, "vod_id").isEmpty()) vod.addProperty("vod_id", JianpianApi.findString(source, "id"));
        if (string(vod, "vod_name").isEmpty()) vod.addProperty("vod_name", "荐片");

        LinkedHashMap<String, List<String>> routes = new LinkedHashMap<>();
        appendRoutes(source, "source_list_source", routes);
        appendRoutes(source, "vip_source_list_source", routes);
        appendFtpRoutes(source, routes);

        List<String> from = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : routes.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            from.add(entry.getKey());
            groups.add(join(entry.getValue(), "#"));
        }
        vod.addProperty("vod_play_from", join(from, "$$$"));
        vod.addProperty("vod_play_url", join(groups, "$$$"));
        return vod;
    }

    private static void appendRoutes(JsonObject source, String field,
                                     LinkedHashMap<String, List<String>> routes) {
        for (JsonElement lineElement : JianpianApi.array(source, field)) {
            JsonObject line = unwrap(lineElement, "line", "provider");
            if (line == null) continue;
            String name = firstNonEmpty(JianpianApi.findString(line, "name", "source_name", "title"), "荐片线路");
            List<String> episodes = routes.get(name);
            if (episodes == null) {
                episodes = new ArrayList<>();
                routes.put(name, episodes);
            }
            JsonArray sourceList = JianpianApi.array(line, "source_list");
            if (sourceList.size() == 0) sourceList = JianpianApi.array(line, "episodes");
            for (JsonElement episodeElement : sourceList) {
                addEpisode(episodes, episodeElement);
            }
        }
    }

    private static void appendFtpRoutes(JsonObject source,
                                        LinkedHashMap<String, List<String>> routes) {
        List<String> episodes = new ArrayList<>();
        for (JsonElement element : JianpianApi.array(source, "ftp_list")) addEpisode(episodes, element);
        if (!episodes.isEmpty()) routes.put("FTP线路", episodes);
    }

    private static void addEpisode(List<String> episodes, JsonElement element) {
        JsonObject episode = unwrap(element, "episode", "item");
        if (episode == null) return;
        String url = JianpianApi.findString(episode, "url", "play_url", "playback_url", "media_url");
        if (url.isEmpty()) return;
        String title = JianpianApi.findString(episode, "source_name", "weight", "name", "title", "sort");
        if (title.isEmpty()) title = "第" + (episodes.size() + 1) + "集";
        else if (title.matches("\\d+")) title = "第" + title + "集";
        String value = title + "$" + url;
        for (String existing : episodes) {
            if (existing.endsWith("$" + url)) return;
        }
        episodes.add(value);
    }

    static JsonObject mapVod(JsonObject source) {
        JsonObject vod = new JsonObject();
        String id = JianpianApi.findString(source, "id", "work_id", "video_id");
        String title = JianpianApi.findString(source, "title", "name", "original_name");
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", title.isEmpty() ? id : title);
        vod.addProperty("vod_pic", image(JianpianApi.findString(source, "thumbnail", "tvimg", "poster_url", "cover_url")));
        vod.addProperty("vod_year", firstYear(source));
        vod.addProperty("vod_area", names(source, "areas", "area", "region"));
        vod.addProperty("vod_actor", names(source, "actors", "actor"));
        vod.addProperty("vod_director", names(source, "directors", "director"));
        vod.addProperty("vod_class", names(source, "types", "category", "kind"));
        vod.addProperty("vod_score", JianpianApi.findString(source, "score", "rating"));
        vod.addProperty("vod_remarks", firstNonEmpty(
                JianpianApi.findString(source, "mask", "remarks", "badge"),
                JianpianApi.findString(source, "episodes_count")));
        vod.addProperty("vod_content", JianpianApi.findString(source, "description", "synopsis", "summary", "content"));
        vod.addProperty("vod_play_from", "");
        vod.addProperty("vod_play_url", "");
        return vod;
    }

    private static String firstYear(JsonObject source) {
        String value = JianpianApi.findString(source, "year", "release_year");
        if (!value.isEmpty()) return value;
        JsonArray years = JianpianApi.array(source, "years");
        if (years.size() > 0 && years.get(0).isJsonObject()) {
            return JianpianApi.findString(years.get(0).getAsJsonObject(), "year", "name");
        }
        return "";
    }

    private static String names(JsonObject source, String... keys) {
        String value = JianpianApi.findString(source, keys);
        if (!value.isEmpty() && !value.startsWith("[")) return value;
        for (String key : keys) {
            JsonArray array = JianpianApi.array(source, key);
            if (array.size() == 0) continue;
            List<String> names = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    String name = JianpianApi.findString(element.getAsJsonObject(), "name", "title", "area");
                    if (!name.isEmpty()) names.add(name);
                } else {
                    String name = element.getAsString().trim();
                    if (!name.isEmpty()) names.add(name);
                }
            }
            if (!names.isEmpty()) return join(names, ",");
        }
        return "";
    }

    private static String image(String value) {
        if (TextUtils.isEmpty(value)) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return IMAGE_ORIGIN + (value.startsWith("/") ? value : "/" + value);
    }

    private static JsonArray single(JsonObject value) {
        JsonArray result = new JsonArray();
        result.add(value);
        return result;
    }

    private static JsonObject unwrap(JsonElement element, String... keys) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject()) return object.getAsJsonObject(key);
        }
        return object;
    }

    private static String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private static String string(JsonObject object, String key) {
        return JianpianApi.findString(object, key);
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values != null) for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value == null ? "" : value);
        }
        return result.toString();
    }

    private static String page(JsonArray list, int page, int pageCount, int total) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", Math.max(1, page));
        root.addProperty("pagecount", Math.max(1, pageCount));
        root.addProperty("limit", 20);
        root.addProperty("total", Math.max(total, list.size()));
        root.add("list", list);
        return root.toString();
    }

    private static String emptyPage(String message) {
        return error(message);
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
}
