package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;

/** 把瓜子影视的加密接口转换为 TVBox 标准 CMS JSON。 */
final class GuaziAdapter implements CmsAdapter {
    private static final Map<String, JsonObject> SEARCH_CACHE = new ConcurrentHashMap<>();

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
            return jsonResponse(emptyPage("瓜子影视"));
        } catch (Throwable throwable) {
            return jsonResponse(error("瓜子影视适配失败: " + safeMessage(throwable)));
        }
    }

    private String search(String keyword) throws Exception {
        JsonObject source = GuaziApi.get().search(keyword);
        JsonArray list = new JsonArray();
        JsonArray sourceList = array(source, "list");
        for (JsonElement element : sourceList) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = mapVod(element.getAsJsonObject());
            String id = string(vod, "vod_id");
            if (id.isEmpty()) continue;
            SEARCH_CACHE.put(id, vod.deepCopy());
            list.add(vod);
        }
        return page(list, 1, 1);
    }

    private String detail(String id) throws Exception {
        JsonObject metadata = SEARCH_CACHE.get(id);
        JsonObject source = GuaziApi.get().detail(id);
        JsonObject vod = metadata == null ? new JsonObject() : metadata.deepCopy();
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
        copy(vod, "vod_name", source, "vod_name");
        copy(vod, "vod_pic", source, "vod_pic");
        copy(vod, "vod_year", source, "vod_year");
        copy(vod, "vod_area", source, "vod_area");
        copy(vod, "vod_actor", source, "vod_actor");
        copy(vod, "vod_director", source, "vod_director");
        copy(vod, "vod_class", source, "vod_class");
        copy(vod, "vod_score", source, "vod_scroe");
        copy(vod, "vod_remarks", source, "new_continue");
        copy(vod, "vod_content", source, "d_class");
        vod.addProperty("vod_play_from", "");
        vod.addProperty("vod_play_url", "");
        return vod;
    }

    private static void copy(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        String value = string(source, sourceKey);
        if (!value.isEmpty()) target.addProperty(targetKey, value);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static Map<String, String> parseQuery(String value) {
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : value.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) result.put(pair.substring(0, index), pair.substring(index + 1));
        }
        return result;
    }

    private static String page(JsonArray list, int page, int pageCount) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", page);
        root.addProperty("pagecount", pageCount);
        root.addProperty("limit", 20);
        root.addProperty("total", list.size());
        root.add("list", list);
        return root.toString();
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
}
