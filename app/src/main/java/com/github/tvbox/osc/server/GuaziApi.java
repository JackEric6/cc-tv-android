package com.github.tvbox.osc.server;

import android.os.Build;
import android.provider.Settings;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.KV;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;

/** 瓜子影视业务接口客户端，负责 token 生命周期和四段请求链路。 */
final class GuaziApi {
    private static final String TOKEN_KEY = "guazi_api_token";
    private static final String DEVICE_KEY = "guazi_device_key";
    private static final Object TOKEN_LOCK = new Object();
    private static final GuaziApi INSTANCE = new GuaziApi();

    private GuaziApi() {
    }

    static GuaziApi get() {
        return INSTANCE;
    }

    JsonObject search(String keyword) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("keywords", keyword);
        params.addProperty("order_val", "0");
        params.addProperty("search_type", "");
        return request("/App/Index/findMoreVod", params);
    }

    JsonArray homeNavigation() throws IOException {
        JsonElement value = requestValue("/App/Index/indexPid", new JsonObject());
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    JsonObject homePage(String pid) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("pid", pid);
        return request("/App/IndexList/index", params);
    }

    JsonObject homePlaylist(String showId, String pid) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("show_id", showId);
        params.addProperty("pid", pid);
        return request("/App/IndexList/hotsList", params);
    }

    JsonObject category(String tid, int page, Map<String, String> filters) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("area", value(filters, "area", "0"));
        params.addProperty("sub", value(filters, "sub", categorySub(tid)));
        params.addProperty("year", value(filters, "year", "0"));
        params.addProperty("pageSize", "30");
        params.addProperty("sort", value(filters, "sort", "d_id"));
        params.addProperty("page", String.valueOf(Math.max(1, page)));
        params.addProperty("tid", tid);
        return request("/App/IndexList/indexList", params);
    }

    JsonObject playInfo(String vodId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("token_id", "");
        params.addProperty("vod_id", vodId);
        params.addProperty("mobile_time", String.valueOf(System.currentTimeMillis() / 1000L));
        params.addProperty("token", KV.get(TOKEN_KEY, ""));
        return request("/App/IndexPlay/playInfo", params);
    }

    JsonObject detail(String vodId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("d_id", vodId);
        return request("/App/Resource/Vod/showOne", params);
    }

    JsonObject episodes(String cloudId, String vodId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("vurl_cloud_id", cloudId);
        params.addProperty("vod_d_id", vodId);
        return request("/App/Resource/Vurl/show", params);
    }

    String play(String vodId, String domainType, String vurlId, String resolution, String type) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("vod_id", vodId);
        params.addProperty("domain_type", domainType);
        params.addProperty("vurl_id", vurlId);
        params.addProperty("resolution", resolution);
        params.addProperty("type", type);
        JsonObject result = request("/App/Resource/VurlDetail/showOne", params);
        String url = findPlayableUrl(result);
        if (url.isEmpty()) throw new IOException("瓜子播放接口没有返回媒体地址");
        return url;
    }

    private JsonObject request(String path, JsonObject params) throws IOException {
        JsonElement value = requestValue(path, params);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private JsonElement requestValue(String path, JsonObject params) throws IOException {
        synchronized (TOKEN_LOCK) {
            String token = KV.get(TOKEN_KEY, "");
            if (token.isEmpty()) token = registerLocked();
            try {
                return doRequestValue(path, params, token);
            } catch (GuaziAuthException authException) {
                KV.put(TOKEN_KEY, "");
                String renewed = registerLocked();
                return doRequestValue(path, params, renewed);
            }
        }
    }

    private String registerLocked() throws IOException {
        String oldKey = stableDeviceKey();
        String deviceKey = KV.get(DEVICE_KEY, "");
        if (deviceKey.isEmpty()) deviceKey = newDeviceKey();
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonObject params = new JsonObject();
            params.addProperty("old_key", oldKey);
            params.addProperty("new_key", deviceKey);
            params.addProperty("phone_type", "1");
            params.addProperty("code", "");
            try {
                JsonObject result = doRequest("/App/Authentication/Device/signUp", params, "");
                String token = string(result, "token");
                if (!token.isEmpty()) {
                    KV.put(DEVICE_KEY, deviceKey);
                    KV.put(TOKEN_KEY, token);
                    return token;
                }
            } catch (GuaziAuthException ignored) {
                // 设备号已注册时换一个 new_key 再注册，避免把错误 token 留在本地。
            }
            deviceKey = newDeviceKey();
        }
        throw new IOException("瓜子设备注册失败");
    }

    private JsonObject doRequest(String path, JsonObject params, String token) throws IOException {
        JsonElement value = doRequestValue(path, params, token);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private JsonElement doRequestValue(String path, JsonObject params, String token) throws IOException {
        try {
            long time = System.currentTimeMillis() / 1000L;
            Map<String, String> values = GuaziCrypto.createForm(params, token, time);
            FormBody.Builder body = new FormBody.Builder();
            for (Map.Entry<String, String> entry : values.entrySet()) body.add(entry.getKey(), entry.getValue());
            Request request = new Request.Builder()
                    .url(GuaziCrypto.BASE_URL + path)
                    .header("api-ver", GuaziCrypto.API_VERSION)
                    .header("code", "GZ0007")
                    .header("lang", "zh_cn")
                    .header("PackageName", GuaziCrypto.PACKAGE_NAME)
                    .header("Ver", GuaziCrypto.API_VERSION)
                    .header("Version", GuaziCrypto.VERSION_CODE)
                    .header("Referer", GuaziCrypto.BASE_URL)
                    .post(body.build())
                    .build();
            try (Response response = OkHttp.client(25000).newCall(request).execute()) {
                if (response.body() == null) throw new IOException("瓜子接口响应为空");
                String raw = response.body().string();
                int code = GuaziCrypto.responseCode(raw);
                if (code != 200) {
                    if (code == 401 || code == 403 || GuaziCrypto.responseMessage(raw).contains("token")) {
                        throw new GuaziAuthException(GuaziCrypto.responseMessage(raw));
                    }
                    throw new IOException("瓜子接口错误: " + GuaziCrypto.responseMessage(raw));
                }
                return GuaziCrypto.decodeResponseValue(raw);
            }
        } catch (GuaziAuthException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("瓜子接口请求失败: " + throwable.getMessage(), throwable);
        }
    }

    private String stableDeviceKey() {
        try {
            String androidId = Settings.Secure.getString(
                    App.getInstance().getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null && !androidId.trim().isEmpty()) return "android-" + androidId.trim();
        } catch (Throwable ignored) {
        }
        return "android-" + Build.MANUFACTURER + "-" + Build.MODEL;
    }

    private String newDeviceKey() {
        return "avbox-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values == null ? "" : values.get(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String categorySub(String tid) {
        if ("1".equals(tid)) return "5";
        if ("2".equals(tid)) return "12";
        if ("3".equals(tid)) return "22";
        if ("4".equals(tid)) return "30";
        return "";
    }

    private static String findPlayableUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            String value = element.getAsString().trim();
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            return value.startsWith("http") && (lower.contains(".m3u8") || lower.contains(".mp4")
                    || lower.contains(".mpd")) ? value : "";
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String value = findPlayableUrl(child);
                if (!value.isEmpty()) return value;
            }
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        String[] priority = {"url", "play_url", "playUrl", "m3u8", "video_url", "videoUrl", "data", "result"};
        for (String key : priority) {
            if (object.has(key)) {
                String value = findPlayableUrl(object.get(key));
                if (!value.isEmpty()) return value;
            }
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String value = findPlayableUrl(entry.getValue());
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static final class GuaziAuthException extends IOException {
        GuaziAuthException(String message) {
            super(message);
        }
    }
}
