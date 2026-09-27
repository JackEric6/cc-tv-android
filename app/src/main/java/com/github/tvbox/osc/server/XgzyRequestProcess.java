package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 西瓜资源 CMS 适配:合并顶层分类并统一处理搜索、详情请求。 */
final class XgzyRequestProcess implements RequestProcess {
    private static final String PREFIX = "/xgzy";
    private static final String SOURCE_PARAM = "source";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final Gson GSON = new Gson();
    private static final OkHttpClient DIRECT_CLIENT = new OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final int CHILD_FETCH_CONCURRENCY = 4;
    private static final int CHILD_FETCH_TIMEOUT_SECONDS = 7;
    private static final int DETAIL_BATCH_SIZE = 20;
    private static final OkHttpClient CHILD_CATEGORY_CLIENT = DIRECT_CLIENT.newBuilder()
            .connectTimeout(CHILD_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CHILD_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CHILD_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private static final Map<String, String[]> TOP_LEVEL_CHILDREN = new HashMap<>();

    static {
        TOP_LEVEL_CHILDREN.put("1", new String[]{"6", "7", "8", "9", "10", "11", "12", "20", "34", "37"});
        TOP_LEVEL_CHILDREN.put("2", new String[]{"13", "14", "15", "16", "21", "22", "23", "24"});
        TOP_LEVEL_CHILDREN.put("3", new String[]{"25", "26", "27", "28"});
        TOP_LEVEL_CHILDREN.put("4", new String[]{"29", "30", "31", "32", "33"});
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && (PREFIX.equals(fileName) || fileName.startsWith(PREFIX + "/"));
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        try {
            if (params == null) params = session.getParms();
            String source = value(params, SOURCE_PARAM);
            if (!isAllowedSource(source)) {
                return jsonResponse(error("source 参数必须是 xgzyapi.com 地址"));
            }
            String ids = value(params, "ids");
            String wd = value(params, "wd");
            String type = value(params, "t");
            JsonObject result;
            if (!ids.isEmpty()) {
                result = fetchObject(source, mapOf("ac", "detail", "ids", ids));
            } else if (!wd.isEmpty()) {
                result = fetchObject(source, mapOf("ac", "detail", "wd", wd));
                if (isEmptyList(result)) {
                    result = fetchObject(source, mapOf("ac", "list", "wd", wd));
                }
            } else if (!type.isEmpty()) {
                result = category(source, type, value(params, "pg"));
            } else {
                result = fetchObject(source, mapOf("ac", "list", "pg", "1"));
                if (result.has("list") && result.get("list").isJsonArray()) {
                    hydrateImages(source, result.getAsJsonArray("list"));
                }
            }
            return jsonResponse(normalizeResult(result).toString());
        } catch (Throwable th) {
            return jsonResponse(error("西瓜资源适配失败: " + safeMessage(th)));
        }
    }

    private JsonObject category(String source, String type, String page) throws IOException {
        String[] children = TOP_LEVEL_CHILDREN.get(type);
        if (children == null) {
            JsonObject result = fetchObject(source, mapOf("ac", "list", "t", type, "pg", pageOrOne(page)));
            if (result.has("list") && result.get("list").isJsonArray()) {
                hydrateImages(source, result.getAsJsonArray("list"));
            }
            return result;
        }

        List<JsonObject> childrenResults = fetchChildCategories(source, children, pageOrOne(page));
        if (childrenResults.isEmpty()) throw new IOException("all child categories failed");
        JsonObject result = mergeChildCategoryResults(childrenResults, pageOrOne(page));
        JsonArray merged = result.getAsJsonArray("list");
        hydrateImages(source, merged);
        return result;
    }

    /**
     * 西瓜顶层分类需要合并多个子分类。顺序保留配置顺序，慢/失效子类在超时后仅降级为部分结果，
     * 不再让整个首页分类串行等待。
     */
    private List<JsonObject> fetchChildCategories(String source, String[] children, String page) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(CHILD_FETCH_CONCURRENCY, children.length));
        try {
            List<Callable<JsonObject>> tasks = new ArrayList<>();
            for (String child : children) {
                tasks.add(() -> fetchObject(source,
                        mapOf("ac", "list", "t", child, "pg", page), CHILD_CATEGORY_CLIENT));
            }
            List<Future<JsonObject>> futures = executor.invokeAll(
                    tasks, CHILD_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<JsonObject> results = new ArrayList<>();
            for (Future<JsonObject> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    results.add(future.get());
                } catch (ExecutionException ignored) {
                    // 单个子分类暂时失效时，用其余分类保持首页可用。
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("child category fetch interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    static JsonObject mergeChildCategoryResults(List<JsonObject> childrenResults, String page) {
        int total = 0;
        JsonArray merged = new JsonArray();
        JsonArray classes = null;
        for (JsonObject item : childrenResults) {
            if (item == null) continue;
            if (classes == null && item.has("class") && item.get("class").isJsonArray()) {
                classes = item.getAsJsonArray("class");
            }
            total += intValue(item, "total");
            JsonArray list = item.has("list") && item.get("list").isJsonArray()
                    ? item.getAsJsonArray("list") : new JsonArray();
            for (JsonElement element : list) {
                if (merged.size() < 20) merged.add(element);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 1);
        result.addProperty("msg", "数据列表");
        result.addProperty("page", page);
        result.addProperty("pagecount", Math.max(1, (total + 19) / 20));
        result.addProperty("limit", "20");
        result.addProperty("total", total);
        if (classes != null) result.add("class", classes);
        result.add("list", merged);
        return result;
    }

    /** 分类接口的 vod_pic 经常为空；用一次批量详情请求补齐当前页海报。 */
    private void hydrateImages(String source, JsonArray list) {
        ArrayList<String> ids = new ArrayList<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String pic = stringValue(vod, "vod_pic");
            String id = stringValue(vod, "vod_id");
            if (pic.isEmpty() && !id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return;

        Map<String, JsonObject> byId = new HashMap<>();
        for (int start = 0; start < ids.size(); start += DETAIL_BATCH_SIZE) {
            int end = Math.min(start + DETAIL_BATCH_SIZE, ids.size());
            try {
                collectDetails(fetchObject(source,
                        mapOf("ac", "detail", "ids", join(new ArrayList<>(ids.subList(start, end))))), byId);
            } catch (Throwable ignored) {
                // 图片补齐失败不应让分类列表整体失败。
            }
        }
        for (String id : ids) {
            if (byId.containsKey(id)) continue;
            try {
                collectDetails(fetchObject(source, mapOf("ac", "detail", "ids", id)), byId);
            } catch (Throwable ignored) {
                // 单条详情也可能被临时限流,保留原列表。
            }
        }
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            JsonObject detail = byId.get(stringValue(vod, "vod_id"));
            if (detail == null) continue;
            copyImageField(detail, vod, "vod_pic");
            copyImageField(detail, vod, "vod_pic_thumb");
            copyImageField(detail, vod, "vod_pic_slide");
        }
    }

    private void collectDetails(JsonObject details, Map<String, JsonObject> byId) {
        if (details == null || !details.has("list") || !details.get("list").isJsonArray()) return;
        for (JsonElement element : details.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject detail = element.getAsJsonObject();
            String id = stringValue(detail, "vod_id");
            if (!id.isEmpty()) byId.put(id, detail);
        }
    }

    private boolean isEmptyList(JsonObject object) {
        return object == null || !object.has("list") || !object.get("list").isJsonArray()
                || object.getAsJsonArray("list").size() == 0;
    }

    private void copyImageField(JsonObject from, JsonObject to, String key) {
        String value = stringValue(from, key);
        if (!value.isEmpty()) to.addProperty(key, value);
    }

    private String join(ArrayList<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private JsonObject fetchObject(String source, Map<String, String> params) throws IOException {
        return fetchObject(source, params, DIRECT_CLIENT);
    }

    private JsonObject fetchObject(String source, Map<String, String> params, OkHttpClient client) throws IOException {
        HttpUrl.Builder builder = HttpUrl.get(source).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!TextUtils.isEmpty(entry.getValue())) builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
        Request request = new Request.Builder()
                .url(builder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JsonObject object = GSON.fromJson(response.body().string(), JsonObject.class);
            if (object == null) throw new IOException("empty JSON");
            return object;
        }
    }

    /** 西瓜图片 CDN 偶尔返回协议相对地址；统一为可直接交给 Coil 的绝对 HTTPS 地址。 */
    private JsonObject normalizeResult(JsonObject result) {
        if (result == null || !result.has("list") || !result.get("list").isJsonArray()) return result;
        for (JsonElement element : result.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            normalizeImageField(vod, "vod_pic");
            normalizeImageField(vod, "vod_pic_thumb");
            normalizeImageField(vod, "vod_pic_slide");
        }
        return result;
    }

    private void normalizeImageField(JsonObject vod, String key) {
        if (!vod.has(key) || vod.get(key).isJsonNull()) return;
        String value = vod.get(key).getAsString().trim();
        if (value.startsWith("//")) value = "https:" + value;
        if (!value.isEmpty()) vod.addProperty(key, value);
    }

    private boolean isAllowedSource(String source) {
        if (TextUtils.isEmpty(source)) return false;
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            return host.equals("caiji.xgzyapi.com") || host.endsWith(".xgzyapi.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private String pageOrOne(String page) {
        return TextUtils.isEmpty(page) ? "1" : page;
    }

    private static int intValue(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String stringValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private String error(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", "20");
        root.addProperty("total", 0);
        root.add("list", new JsonArray());
        return root.toString();
    }

    private String safeMessage(Throwable th) {
        String message = th == null ? "" : th.getMessage();
        return TextUtils.isEmpty(message) ? th.getClass().getSimpleName() : message;
    }

    private NanoHTTPD.Response jsonResponse(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8",
                body == null ? "{}" : body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
