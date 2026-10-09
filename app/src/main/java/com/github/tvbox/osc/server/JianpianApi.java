package com.github.tvbox.osc.server;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** 荐片公开 v2 API 客户端。详情响应已经包含可播放线路，不再依赖旧运行时加密协议。 */
final class JianpianApi {
    private static final JianpianApi INSTANCE = new JianpianApi();
    private static final String ORIGIN = "https://fan123.hzhnl.com";
    private static final String REFERER = ORIGIN;
    private static final String USER_AGENT = "Mozilla/5.0 packageName:com.jp3.xg3";

    private JianpianApi() {
    }

    static JianpianApi get() {
        return INSTANCE;
    }

    JsonObject search(String keyword, int page, int pageSize) throws IOException {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("key", keyword == null ? "" : keyword);
        query.put("category_id", "88");
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("pageSize", String.valueOf(Math.max(1, pageSize)));
        return getJson("/api/v2/search/videoV2", query);
    }

    JsonObject detail(String workId) throws IOException {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("id", workId == null ? "" : workId);
        JsonObject outer = getJson("/api/video/detailv2", query);
        if (outer.has("data") && outer.get("data").isJsonObject()) return outer.getAsJsonObject("data");
        return outer;
    }

    /** 兼容旧的本地播放/P2P桥接；优先从详情中直接取对应线路和集数。 */
    String access(String workId, String episodeKey, String episodeIndex, String lineKey) throws IOException {
        return access(workId, episodeKey, episodeIndex, lineKey, "playback");
    }

    String access(String workId, String episodeKey, String episodeIndex,
                  String lineKey, String deliveryType) throws IOException {
        JsonObject detail = detail(workId);
        String wantedIndex = firstNonEmpty(episodeIndex, episodeKey);
        for (String sourceKey : new String[]{"source_list_source", "vip_source_list_source"}) {
            JsonArray lines = array(detail, sourceKey);
            for (JsonElement lineElement : lines) {
                if (!lineElement.isJsonObject()) continue;
                JsonObject line = lineElement.getAsJsonObject();
                String currentLine = findString(line, "id", "source_id", "name", "source_name");
                if (!lineKey.isEmpty() && !lineKey.equals(currentLine)
                        && !lineKey.equals(findString(line, "name", "source_name"))) continue;
                for (JsonElement episodeElement : array(line, "source_list", "episodes", "list")) {
                    if (!episodeElement.isJsonObject()) continue;
                    JsonObject episode = episodeElement.getAsJsonObject();
                    String currentEpisode = findString(episode, "sort", "weight", "source_name", "name", "id");
                    if (!wantedIndex.isEmpty() && !wantedIndex.equals(currentEpisode)
                            && !wantedIndex.equals(findString(episode, "source_name", "weight"))) continue;
                    String url = findString(episode, "url", "play_url", "playback_url");
                    if (!url.isEmpty()) return url;
                }
            }
        }
        throw new IOException("荐片详情没有找到对应媒体地址");
    }

    private JsonObject getJson(String path, Map<String, String> query) throws IOException {
        HttpUrl.Builder builder = HttpUrl.get(ORIGIN + path).newBuilder();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() != null) builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        Request request = new Request.Builder()
                .url(builder.build())
                .header("Accept", "application/json")
                .header("Referer", REFERER)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = OkHttp.client(30000).newCall(request).execute()) {
            if (response.body() == null) throw new IOException("荐片接口响应为空");
            String raw = response.body().string();
            if (!response.isSuccessful()) throw new IOException("荐片接口 HTTP " + response.code());
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("荐片接口请求失败: " + throwable.getMessage(), throwable);
        }
    }

    static JsonArray array(JsonObject object, String... keys) {
        if (object == null || keys == null) return new JsonArray();
        for (String key : keys) {
            try {
                if (object.has(key) && object.get(key).isJsonArray()) return object.getAsJsonArray(key);
            } catch (Throwable ignored) {
            }
        }
        return new JsonArray();
    }

    static String findString(JsonObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            try {
                if (object.has(key) && !object.get(key).isJsonNull()) {
                    String value = object.get(key).getAsString().trim();
                    if (!value.isEmpty()) return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        if (values != null) for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
