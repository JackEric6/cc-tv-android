package com.github.tvbox.osc.server;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** 荐片公开 v2 API 客户端。详情响应已经包含可播放线路，不再依赖旧运行时加密协议。 */
final class JianpianApi {
    private static final JianpianApi INSTANCE = new JianpianApi();
    private static final String USER_AGENT = "Mozilla/5.0 packageName:com.jp3.xg3";
    private static final long DISCOVERY_REFRESH_MILLIS = 10 * 60 * 1000L;
    private static final long DISCOVERY_TIMEOUT_MILLIS = 8000L;

    private volatile String lastSuccessfulOrigin = "";
    private volatile String discoveredOrigins = "";
    private volatile long discoveryCheckedAt;

    private JianpianApi() {
    }

    static JianpianApi get() {
        return INSTANCE;
    }

    JsonObject search(String keyword, int page, int pageSize) throws IOException {
        return search(keyword, "88", page, pageSize);
    }

    JsonObject search(String keyword, String categoryId, int page, int pageSize) throws IOException {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("key", keyword == null ? "" : keyword);
        query.put("category_id", categoryId == null || categoryId.trim().isEmpty()
                ? "88" : categoryId.trim());
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
        List<String> origins = candidateOrigins();
        List<String> failures = new ArrayList<>();
        IOException lastFailure = null;
        for (String origin : origins) {
            try {
                JsonObject result = getJson(origin, path, query);
                lastSuccessfulOrigin = origin;
                return result;
            } catch (ApiHttpException exception) {
                lastFailure = exception;
                failures.add(HttpUrl.get(origin).host() + " HTTP " + exception.statusCode);
                if (!JianpianOriginResolver.shouldFailover(exception.statusCode)) throw exception;
                if (origin.equals(lastSuccessfulOrigin)) lastSuccessfulOrigin = "";
            } catch (IOException exception) {
                lastFailure = exception;
                failures.add(HttpUrl.get(origin).host() + " " + exception.getMessage());
                if (origin.equals(lastSuccessfulOrigin)) lastSuccessfulOrigin = "";
            }
        }
        StringBuilder failureSummary = new StringBuilder();
        for (String failure : failures) {
            if (failureSummary.length() > 0) failureSummary.append(", ");
            failureSummary.append(failure);
        }
        throw new IOException("荐片接口所有候选域名均请求失败: " + failureSummary, lastFailure);
    }

    private JsonObject getJson(String origin, String path, Map<String, String> query) throws IOException {
        HttpUrl.Builder builder = HttpUrl.get(origin + path).newBuilder();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() != null) builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        Request request = new Request.Builder()
                .url(builder.build())
                .header("Accept", "application/json")
                .header("Referer", origin + "/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = OkHttp.client(30000).newCall(request).execute()) {
            if (response.body() == null) throw new IOException("荐片接口响应为空");
            String raw = response.body().string();
            if (!response.isSuccessful()) throw new ApiHttpException(response.code());
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Throwable throwable) {
                throw new IOException("荐片接口返回了无效 JSON", throwable);
            }
        } catch (Throwable throwable) {
            if (throwable instanceof IOException) throw (IOException) throwable;
            throw new IOException("荐片接口请求失败: " + throwable.getMessage(), throwable);
        }
    }

    private List<String> candidateOrigins() {
        long now = System.currentTimeMillis();
        if (now - discoveryCheckedAt >= DISCOVERY_REFRESH_MILLIS) {
            synchronized (this) {
                now = System.currentTimeMillis();
                if (now - discoveryCheckedAt >= DISCOVERY_REFRESH_MILLIS) {
                    discoveredOrigins = discoverOrigins();
                    discoveryCheckedAt = now;
                }
            }
        }
        return JianpianOriginResolver.candidates(lastSuccessfulOrigin, discoveredOrigins);
    }

    private String discoverOrigins() {
        Request request = new Request.Builder()
                .url(JianpianOriginResolver.DISCOVERY_URL)
                .header("Accept", "text/plain, */*")
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = OkHttp.client(DISCOVERY_TIMEOUT_MILLIS).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            return response.body().string();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static final class ApiHttpException extends IOException {
        final int statusCode;

        ApiHttpException(int statusCode) {
            super("荐片接口 HTTP " + statusCode);
            this.statusCode = statusCode;
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
