package com.github.tvbox.osc.server;

import android.os.Build;
import android.provider.Settings;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.KV;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 荐片 HTTP API 客户端。所有响应统一在本地解密后交给适配器映射。 */
final class JianpianApi {
    private static final String INSTALLATION_KEY = "jianpian_installation_id";
    private static final String CREDENTIAL_KEY = "jianpian_runtime_credential";
    private static final String ORIGIN_KEY = "jianpian_api_origin";
    private static final String DISCOVERY_KEY = "jianpian_api_discovery";
    private static final String DISCOVERY_AT_KEY = "jianpian_api_discovery_at";
    private static final long DISCOVERY_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final JianpianApi INSTANCE = new JianpianApi();
    private static final Object ID_LOCK = new Object();
    private static final Object ORIGIN_LOCK = new Object();
    private volatile List<String> cachedOrigins = Collections.emptyList();

    private JianpianApi() {
    }

    static JianpianApi get() {
        return INSTANCE;
    }

    JsonObject search(String keyword, int page, int pageSize) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", keyword);
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("page_size", String.valueOf(Math.max(1, pageSize)));
        return request("GET", "/search", query, null);
    }

    JsonObject work(String workId) throws IOException {
        return request("GET", "/works/" + encodePath(workId), null, null);
    }

    JsonObject episodes(String workId) throws IOException {
        return request("GET", "/works/" + encodePath(workId) + "/episodes", null, null);
    }

    JsonObject manifest(String workId) throws IOException {
        return request("GET", "/works/" + encodePath(workId) + "/playback-manifest", null, null);
    }

    JsonObject mediaLines(String workId, String episodeKey, String episodeIndex) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        if (episodeKey != null && !episodeKey.isEmpty()) query.put("episode_key", episodeKey);
        if (episodeIndex != null && !episodeIndex.isEmpty()) query.put("episode_index", episodeIndex);
        query.put("delivery_type", "playback");
        return request("GET", "/works/" + encodePath(workId) + "/media-lines", query, null);
    }

    String access(String workId,
                  String episodeKey,
                  String episodeIndex,
                  String lineKey) throws IOException {
        return access(workId, episodeKey, episodeIndex, lineKey, "playback");
    }

    String access(String workId,
                  String episodeKey,
                  String episodeIndex,
                  String lineKey,
                  String deliveryType) throws IOException {
        JsonObject body = new JsonObject();
        try {
            body.addProperty("work_id", Integer.parseInt(workId));
        } catch (Throwable ignored) {
            body.addProperty("work_id", workId);
        }
        if (episodeKey != null && !episodeKey.isEmpty()) body.addProperty("episode_key", episodeKey);
        if (episodeIndex != null && !episodeIndex.isEmpty()) {
            try {
                body.addProperty("episode_index", Integer.parseInt(episodeIndex));
            } catch (NumberFormatException ignored) {
                body.addProperty("episode_index", episodeIndex);
            }
        }
        body.addProperty("preferred_line_key", lineKey);
        body.addProperty("delivery_type", deliveryType == null || deliveryType.isEmpty()
                ? "playback" : deliveryType);
        JsonObject result = request("POST", "/media/access", null, body);
        String url = findString(result, "url", "play_url", "playback_url", "media_url");
        if (url.isEmpty()) throw new IOException("荐片播放接口没有返回媒体地址");
        return url;
    }

    private JsonObject request(String method,
                               String path,
                               Map<String, String> query,
                               JsonObject body) throws IOException {
        IOException last = null;
        List<String> origins = origins();
        for (String origin : origins) {
            try {
                JsonObject result = requestOnce(origin, method, path, query, body);
                rememberOrigin(origin);
                return result;
            } catch (JianpianHttpException exception) {
                last = exception;
                if (!JianpianOriginResolver.shouldFailover(exception.statusCode)) throw exception;
            } catch (IOException exception) {
                last = exception;
            }
        }
        if (last != null) {
            throw new IOException("荐片接口请求失败，已尝试 " + origins.size() + " 个 API 域名: "
                    + last.getMessage(), last);
        }
        throw new IOException("荐片没有可用 API 域名");
    }

    private JsonObject requestOnce(String origin,
                                   String method,
                                   String path,
                                   Map<String, String> query,
                                   JsonObject body) throws IOException {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.get(origin + "/api/v1" + path).newBuilder();
            if (query != null) {
                for (Map.Entry<String, String> entry : query.entrySet()) {
                    if (entry.getValue() != null) urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
                }
            }
            HttpUrl url = urlBuilder.build();
            String requestUri = url.encodedPath()
                    + (url.encodedQuery() == null ? "" : "?" + url.encodedQuery());
            long timestamp = System.currentTimeMillis() / 1000L;
            String requestNonce = JianpianCrypto.base64Url(JianpianCrypto.randomBytes(12));
            byte[] requestBody = new byte[0];
            Request.Builder builder = new Request.Builder().url(url)
                    .header("Accept", "application/json, application/problem+json")
                    .header("Accept-Language", "zh-CN")
                    .header("X-Client-App", JianpianCrypto.CLIENT_APP)
                    .header("X-Client-Platform", JianpianCrypto.CLIENT_PLATFORM)
                    .header("X-Client-Version", JianpianCrypto.API_VERSION)
                    .header("X-Client-Build", "1")
                    .header("X-Device-ID", installationId())
                    .header("X-Device-Model", Build.MANUFACTURER + " " + Build.MODEL)
                    .header("X-OS-Version", Build.VERSION.RELEASE)
                    .header("X-JP-Crypto-Version", JianpianCrypto.CRYPTO_VERSION)
                    .header("X-JP-Runtime-Authorization", "Bearer " + credential())
                    .header("X-JP-Timestamp", String.valueOf(timestamp))
                    .header("X-JP-Nonce", requestNonce);
            if ("POST".equals(method)) {
                byte[] plain = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
                JsonObject envelope = JianpianCrypto.encryptRequest(method, requestUri, timestamp,
                        requestNonce, plain, credential(), installationId());
                requestBody = envelope.toString().getBytes(StandardCharsets.UTF_8);
                builder.header("Content-Type", JianpianCrypto.ENCRYPTED_CONTENT_TYPE)
                        .post(RequestBody.create(MediaType.get(JianpianCrypto.ENCRYPTED_CONTENT_TYPE), requestBody));
            } else {
                builder.get();
            }
            builder.header("X-JP-Signature", JianpianCrypto.requestSignature(method, requestUri,
                    timestamp, requestNonce, requestBody, credential(), installationId()));
            try (Response response = OkHttp.client(45000).newCall(builder.build()).execute()) {
                if (response.body() == null) throw new IOException("荐片接口响应为空");
                String raw = response.body().string();
                if (!response.isSuccessful()) {
                    throw new JianpianHttpException(response.code(),
                            "荐片接口 HTTP " + response.code() + ": " + compactError(raw));
                }
                JsonObject outer = JsonParser.parseString(raw).getAsJsonObject();
                String contentType = response.header("Content-Type", "");
                String cryptoHeader = response.header("X-JP-Crypto-Version", "");
                if ("1".equals(cryptoHeader) || contentType.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(JianpianCrypto.ENCRYPTED_CONTENT_TYPE)) {
                    byte[] plain = JianpianCrypto.decryptResponse(outer, response.code(), method, requestUri,
                            timestamp, requestNonce, credential(), installationId());
                    return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
                }
                return outer;
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("荐片接口请求失败: " + throwable.getMessage(), throwable);
        }
    }

    private List<String> origins() {
        synchronized (ORIGIN_LOCK) {
            long now = System.currentTimeMillis();
            long discoveredAt = KV.get(DISCOVERY_AT_KEY, 0L);
            if (cachedOrigins.isEmpty() || now - discoveredAt >= DISCOVERY_TTL_MS) {
                refreshDiscoveryLocked(now);
            }
            String preferred = KV.get(ORIGIN_KEY, "");
            String discovered = KV.get(DISCOVERY_KEY, "");
            List<String> result = JianpianOriginResolver.candidates(preferred, discovered);
            cachedOrigins = result;
            return result;
        }
    }

    private void refreshDiscoveryLocked(long now) {
        String discovered = KV.get(DISCOVERY_KEY, "");
        try {
            Request request = new Request.Builder().url(JianpianOriginResolver.DISCOVERY_URL)
                    .header("Accept", "text/plain")
                    .get().build();
            try (Response response = OkHttp.client(10000).newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String text = response.body().string().trim();
                    if (!text.isEmpty()) {
                        discovered = text;
                        KV.put(DISCOVERY_KEY, discovered);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 发现服务不可用时继续使用上一次结果和内置备用域名。
        }
        KV.put(DISCOVERY_AT_KEY, now);
        cachedOrigins = JianpianOriginResolver.candidates(KV.get(ORIGIN_KEY, ""), discovered);
    }

    private void rememberOrigin(String origin) {
        String normalized = JianpianOriginResolver.normalize(origin);
        if (normalized == null) return;
        KV.put(ORIGIN_KEY, normalized);
        synchronized (ORIGIN_LOCK) {
            cachedOrigins = JianpianOriginResolver.candidates(normalized,
                    KV.get(DISCOVERY_KEY, ""));
        }
    }

    private String installationId() {
        synchronized (ID_LOCK) {
            String value = KV.get(INSTALLATION_KEY, "");
            if (!value.isEmpty()) return value;
            String androidId = "";
            try {
                androidId = Settings.Secure.getString(App.getInstance().getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Throwable ignored) {
            }
            value = (androidId == null || androidId.trim().isEmpty())
                    ? "jp-" + UUID.randomUUID()
                    : "jp-" + androidId.trim();
            KV.put(INSTALLATION_KEY, value);
            return value;
        }
    }

    private String credential() {
        synchronized (ID_LOCK) {
            String value = KV.get(CREDENTIAL_KEY, "");
            if (!value.isEmpty()) return value;
            value = JianpianCrypto.base64Url(JianpianCrypto.randomBytes(32));
            KV.put(CREDENTIAL_KEY, value);
            return value;
        }
    }

    private static String encodePath(String value) {
        return HttpUrl.parse("https://placeholder.invalid/" + (value == null ? "" : value)).encodedPath()
                .substring(1);
    }

    private static String compactError(String raw) {
        try {
            JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
            String detail = findString(object, "detail", "message", "title", "code");
            if (!detail.isEmpty()) return detail;
        } catch (Throwable ignored) {
        }
        return raw == null ? "" : raw.substring(0, Math.min(240, raw.length()));
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

    private static final class JianpianHttpException extends IOException {
        final int statusCode;

        JianpianHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
