package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;

/** 把荐片作品、线路和动态播放地址转换为 TVBox 标准 CMS JSON。 */
final class JianpianAdapter implements CmsAdapter {
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
        JsonArray list = new JsonArray();
        for (JsonElement element : array(source, "items", "list", "results")) {
            JsonObject item = unwrapObject(element, "work", "vod");
            if (item == null) continue;
            JsonObject vod = mapVod(item);
            if (!string(vod, "vod_id").isEmpty()) list.add(vod);
        }
        int page = intValue(source, "page", 1);
        JsonObject pageObject = object(source, "page");
        int pageCount = pageObject == null ? 1 : (boolValue(pageObject, "has_more") ? page + 1 : page);
        return page(list, page, Math.max(1, pageCount), intValue(source, "found", list.size()));
    }

    private String detail(String id) throws Exception {
        JsonObject work = JianpianApi.get().work(id);
        JsonObject manifest = JianpianApi.get().manifest(id);
        JsonObject episodesResponse = manifest;
        JsonArray episodes = array(manifest, "episodes");
        if (episodes.size() == 0) {
            episodesResponse = JianpianApi.get().episodes(id);
            episodes = array(episodesResponse, "episodes", "items", "list");
        }
        JsonArray lines = array(manifest, "lines", "media_lines");
        if (lines.size() == 0) lines = array(episodesResponse, "lines", "media_lines");
        if (lines.size() == 0) lines = array(JianpianApi.get().mediaLines(id, firstEpisodeKey(episodes), firstEpisodeIndex(episodes)), "items", "lines");

        JsonObject vod = mapVod(work);
        if (string(vod, "vod_id").isEmpty()) vod.addProperty("vod_id", id);
        if (string(vod, "vod_name").isEmpty()) vod.addProperty("vod_name", "荐片 " + id);
        List<String> from = new ArrayList<>();
        List<String> urlGroups = new ArrayList<>();
        for (JsonElement lineElement : lines) {
            JsonObject line = unwrapObject(lineElement, "line", "provider");
            if (line == null) continue;
            String lineKey = JianpianApi.findString(line, "key", "line_key", "id");
            if (lineKey.isEmpty() || boolValue(line, "entitlement_required")) continue;
            List<String> episodeUrls = buildEpisodeUrls(id, lineKey, episodes);
            if (episodeUrls.isEmpty()) continue;
            String lineName = JianpianApi.findString(line, "name", "title", "provider");
            from.add(lineName.isEmpty() ? "荐片线路" : lineName);
            urlGroups.add(join(episodeUrls, "#"));
        }
        vod.addProperty("vod_play_from", join(from, "$$$"));
        vod.addProperty("vod_play_url", join(urlGroups, "$$$"));
        JsonArray list = new JsonArray();
        list.add(vod);
        return page(list, 1, 1, 1);
    }

    private List<String> buildEpisodeUrls(String workId, String lineKey, JsonArray episodes) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < episodes.size(); i++) {
            JsonObject episode = unwrapObject(episodes.get(i), "episode", "item");
            if (episode == null) continue;
            String episodeKey = JianpianApi.findString(episode, "key", "episode_key", "id");
            String index = numberString(episode, "index", "episode_index");
            if (index.isEmpty()) index = String.valueOf(i);
            String title = JianpianApi.findString(episode, "title", "name", "label");
            try {
                HttpUrl url = HttpUrl.get("http://127.0.0.1:" + RemoteServer.serverPort + "/jianpian/play.m3u8")
                        .newBuilder()
                        .addQueryParameter("work_id", workId)
                        .addQueryParameter("episode_key", episodeKey)
                        .addQueryParameter("episode_index", index)
                        .addQueryParameter("line_key", lineKey)
                        .build();
                values.add((title.isEmpty() ? "第" + (i + 1) + "集" : title) + "$" + url);
            } catch (Throwable ignored) {
            }
        }
        return values;
    }

    static JsonObject mapVod(JsonObject source) {
        JsonObject vod = new JsonObject();
        copy(vod, "vod_id", source, "id", "work_id");
        copy(vod, "vod_name", source, "title", "name");
        copy(vod, "vod_pic", source, "poster_url", "cover_url");
        copy(vod, "vod_year", source, "year", "release_year");
        copy(vod, "vod_area", source, "area", "region");
        copy(vod, "vod_actor", source, "actors", "actor");
        copy(vod, "vod_director", source, "directors", "director");
        copy(vod, "vod_class", source, "kind", "category");
        copy(vod, "vod_score", source, "score", "rating");
        copy(vod, "vod_remarks", source, "remarks", "badge");
        copy(vod, "vod_content", source, "synopsis", "summary", "description");
        vod.addProperty("vod_play_from", "");
        vod.addProperty("vod_play_url", "");
        return vod;
    }

    private static JsonArray array(JsonObject object, String... keys) {
        if (object == null) return new JsonArray();
        for (String key : keys) {
            try {
                if (object.has(key) && object.get(key).isJsonArray()) return object.getAsJsonArray(key);
            } catch (Throwable ignored) {
            }
        }
        return new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && object.get(key).isJsonObject()
                    ? object.getAsJsonObject(key) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject unwrapObject(JsonElement value, String... keys) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonObject()) return object.getAsJsonObject(key);
        }
        return object;
    }

    private static void copy(JsonObject target, String targetKey, JsonObject source, String... sourceKeys) {
        String value = JianpianApi.findString(source, sourceKeys);
        if (!value.isEmpty()) target.addProperty(targetKey, value);
    }

    private static String firstEpisodeKey(JsonArray episodes) {
        if (episodes.size() == 0) return "";
        JsonObject episode = unwrapObject(episodes.get(0), "episode", "item");
        return episode == null ? "" : JianpianApi.findString(episode, "key", "episode_key", "id");
    }

    private static String firstEpisodeIndex(JsonArray episodes) {
        if (episodes.size() == 0) return "";
        JsonObject episode = unwrapObject(episodes.get(0), "episode", "item");
        return episode == null ? "0" : numberString(episode, "index", "episode_index");
    }

    private static String numberString(JsonObject object, String... keys) {
        for (String key : keys) {
            try {
                if (object != null && object.has(key) && !object.get(key).isJsonNull()) return object.get(key).getAsString();
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String page(JsonArray list, int page, int pageCount, int total) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", page);
        root.addProperty("pagecount", pageCount);
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
}
