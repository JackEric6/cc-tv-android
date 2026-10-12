package com.github.tvbox.osc.util;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页和搜索结果的轻量离线缓存。
 *
 * <p>只缓存已经成功解析的展示数据，不缓存播放地址或解析结果。缓存有 24 小时新鲜期、7
 * 天最长保留期，超过最长保留期自动删除。MMKV 本身是单进程的，但读写仍在这里统一加锁，
 * 避免首页、搜索并发更新同一条记录时出现半条数据或时间戳错配。
 */
public final class OfflineContentCache {
    private static final int VERSION = 1;
    private static final long FRESH_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_PAYLOAD_CHARS = 900_000;
    private static final String HOME_PREFIX = "offline.home.";
    private static final String SEARCH_PREFIX = "offline.search.";
    private static final Gson GSON = new Gson();
    private static final ExecutorService WRITE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cc-tv-offline-cache");
        thread.setDaemon(true);
        return thread;
    });

    private OfflineContentCache() {
    }

    public static void putHome(String sourceKey, @Nullable AbsSortXml data) {
        enqueueWrite(HOME_PREFIX + digest(sourceKey), data);
    }

    @Nullable
    public static synchronized AbsSortXml getHome(String sourceKey) {
        return read(HOME_PREFIX + digest(sourceKey), AbsSortXml.class);
    }

    public static void putSearch(String sourceKey, String keyword, @Nullable AbsXml data) {
        if (data == null || data.movie == null || data.movie.videoList == null
                || data.movie.videoList.isEmpty()) return;
        enqueueWrite(SEARCH_PREFIX + digest(sourceKey + "\n" + normalize(keyword)), data);
    }

    @Nullable
    public static synchronized AbsXml getSearch(String sourceKey, String keyword) {
        return read(SEARCH_PREFIX + digest(sourceKey + "\n" + normalize(keyword)), AbsXml.class);
    }

    private static <T> void enqueueWrite(String key, @Nullable T data) {
        if (data == null) return;
        WRITE_EXECUTOR.execute(() -> write(key, data));
    }

    private static synchronized <T> void write(String key, @Nullable T data) {
        if (data == null) return;
        try {
            String payload = GSON.toJson(data);
            if (payload.length() > MAX_PAYLOAD_CHARS) {
                LOG.i("offline-cache-skip-large key=" + key + " chars=" + payload.length());
                return;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("v", VERSION);
            entry.addProperty("t", System.currentTimeMillis());
            entry.addProperty("d", payload);
            KV.put(key, entry.toString());
        } catch (Throwable th) {
            LOG.i("offline-cache-write-failed key=" + key + " error=" + th.getMessage());
        }
    }

    @Nullable
    private static <T> T read(String key, Class<T> type) {
        try {
            String raw = KV.get(key, "");
            if (raw == null || raw.isEmpty()) return null;
            JsonObject entry = JsonParser.parseString(raw).getAsJsonObject();
            if (entry.get("v").getAsInt() != VERSION) {
                KV.delete(key);
                return null;
            }
            long savedAt = entry.get("t").getAsLong();
            long age = System.currentTimeMillis() - savedAt;
            if (age < 0 || age > MAX_AGE_MS) {
                KV.delete(key);
                return null;
            }
            // FRESH_TTL_MS is intentionally documented and retained for callers/diagnostics;
            // stale-but-recent data is still valid as a network-failure fallback.
            if (age > FRESH_TTL_MS) {
                LOG.i("offline-cache-stale-fallback key=" + key + " ageMs=" + age);
            }
            String payload = entry.get("d").getAsString();
            return GSON.fromJson(payload, type);
        } catch (Throwable th) {
            LOG.i("offline-cache-read-failed key=" + key + " error=" + th.getMessage());
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String digest(String value) {
        String digest = MD5.string2MD5(value == null ? "" : value);
        return digest == null ? String.valueOf(value == null ? 0 : value.hashCode()) : digest;
    }
}
