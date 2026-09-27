package com.github.tvbox.osc.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/** KKT影视 CMS 响应整理:保留播放器线路,并把可直接播放的 HLS 线路排到前面。 */
final class KktvsCmsParser {
    private static final Pattern MEDIA_URL = Pattern.compile(
            "(?i)(?:https?:)?//[^\\s\\\"'<>\\\\]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv|\\.webm|\\.mov|\\.ts)(?:\\?[^\\s\\\"'<>\\\\]*)?");
    private static final Pattern URL_FIELD = Pattern.compile(
            "(?i)[\\\"']?(?:url|playurl|play_url|file|src|video)[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"'\\r\\n]+)[\\\"']");

    private KktvsCmsParser() {
    }

    static String normalize(String response) {
        if (response == null || response.trim().isEmpty()) return response;
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonObject()) return response;
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("list") && root.get("list").isJsonArray()) {
                for (JsonElement item : root.getAsJsonArray("list")) {
                    if (item.isJsonObject()) normalizeVod(item.getAsJsonObject());
                }
            }
            return root.toString();
        } catch (Throwable ignored) {
            return response;
        }
    }

    static JsonObject parseObject(String response) {
        try {
            JsonElement parsed = JsonParser.parseString(response == null ? "" : response);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void normalizeVod(JsonObject vod) {
        if (vod == null || !vod.has("vod_play_from") || !vod.has("vod_play_url")) return;
        String from = stringValue(vod, "vod_play_from");
        String urls = stringValue(vod, "vod_play_url");
        if (from.isEmpty() || urls.isEmpty()) return;

        String[] flags = split(from);
        String[] playUrls = split(urls);
        String[] servers = split(stringValue(vod, "vod_play_server"));
        String[] notes = split(stringValue(vod, "vod_play_note"));
        List<PlayGroup> groups = new ArrayList<>();
        for (int i = 0; i < flags.length && i < playUrls.length; i++) {
            String flag = flags[i].trim();
            String playUrl = normalizePlayUrl(playUrls[i]);
            if (flag.isEmpty() || playUrl.isEmpty()) continue;
            groups.add(new PlayGroup(
                    flag,
                    playUrl,
                    i < servers.length ? servers[i].trim() : "",
                    i < notes.length ? notes[i].trim() : "",
                    priority(flag, playUrl),
                    i));
        }
        if (groups.isEmpty()) {
            vod.addProperty("vod_play_from", "");
            vod.addProperty("vod_play_url", "");
            if (vod.has("vod_play_server")) vod.addProperty("vod_play_server", "");
            if (vod.has("vod_play_note")) vod.addProperty("vod_play_note", "");
            return;
        }

        // Collections.sort is stable, so equal-priority lines retain the source order.
        Collections.sort(groups, Comparator.comparingInt((PlayGroup group) -> group.priority)
                .thenComparingInt(group -> group.originalIndex));
        vod.addProperty("vod_play_from", joinGroups(groups, Field.FROM));
        vod.addProperty("vod_play_url", joinGroups(groups, Field.URL));
        if (vod.has("vod_play_server")) vod.addProperty("vod_play_server", joinGroups(groups, Field.SERVER));
        if (vod.has("vod_play_note")) vod.addProperty("vod_play_note", joinGroups(groups, Field.NOTE));
    }

    static void reorderList(JsonObject root, List<String> ids) {
        if (root == null || ids == null || ids.isEmpty() || !root.has("list") || !root.get("list").isJsonArray()) return;
        JsonArray current = root.getAsJsonArray("list");
        Map<String, JsonElement> byId = new HashMap<>();
        for (JsonElement item : current) {
            if (!item.isJsonObject()) continue;
            String id = stringValue(item.getAsJsonObject(), "vod_id");
            if (!id.isEmpty()) byId.put(id, item);
        }
        JsonArray ordered = new JsonArray();
        for (String id : ids) {
            JsonElement item = byId.get(id);
            if (item != null) ordered.add(item);
        }
        if (ordered.size() > 0) root.add("list", ordered);
    }

    private static String normalizePlayUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";

        // CMS 用 # 分隔剧集、用第一个 $ 分隔集名和地址;必须逐集处理,
        // 否则 hxplayer/2mplayer 的整条线路不是一个合法 URL,无法解包。
        String[] episodes = raw.split("#", -1);
        StringBuilder result = new StringBuilder(raw.length());
        for (String episode : episodes) {
            if (result.length() > 0) result.append('#');
            int separator = episode.indexOf('$');
            if (separator <= 0 || separator >= episode.length() - 1) {
                result.append(normalizeMediaUrl(episode));
                continue;
            }
            result.append(episode, 0, separator + 1);
            result.append(normalizeMediaUrl(episode.substring(separator + 1)));
        }
        return result.toString();
    }

    static String normalizeMediaUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.startsWith("//")) url = "https:" + url;

        // 部分历史数据把真实地址包在播放器页的 url/play 参数中,优先还原媒体地址。
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed != null) {
                String nested = parsed.queryParameter("url");
                if (nested == null || nested.trim().isEmpty()) nested = parsed.queryParameter("play");
                if (nested == null || nested.trim().isEmpty()) nested = parsed.queryParameter("playurl");
                if (nested != null && isHttpUrl(nested) && isDirectMedia(nested)) {
                    return nested.trim();
                }
            }
        } catch (Throwable ignored) {
            // URL 不符合 OkHttp 规范时保留原值,交给应用后续通用解析链处理。
        }
        return url;
    }

    /** 从播放器页/JSON 响应中提取真实媒体地址,兼容 hxplayer/2mplayer 常见返回格式。 */
    static String extractMediaUrl(String body, String baseUrl) {
        if (body == null || body.trim().isEmpty()) return "";
        String content = unescape(body);

        Matcher fieldMatcher = URL_FIELD.matcher(content);
        while (fieldMatcher.find()) {
            String candidate = resolveCandidate(fieldMatcher.group(1), baseUrl);
            if (isDirectMedia(candidate)) return candidate;
        }

        Matcher mediaMatcher = MEDIA_URL.matcher(content);
        while (mediaMatcher.find()) {
            String candidate = resolveCandidate(mediaMatcher.group(), baseUrl);
            if (isDirectMedia(candidate)) return candidate;
        }
        return "";
    }

    static String directMediaUrl(String value) {
        String candidate = normalizeMediaUrl(value);
        return isDirectMedia(candidate) ? candidate : "";
    }

    private static String resolveCandidate(String value, String baseUrl) {
        String candidate = unescape(value == null ? "" : value.trim());
        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        if (candidate.startsWith("/") && baseUrl != null) {
            try {
                HttpUrl base = HttpUrl.parse(baseUrl);
                if (base != null) candidate = base.resolve(candidate).toString();
            } catch (Throwable ignored) {
                // 保留原值,下面的媒体格式判断会自然拒绝无效地址。
            }
        }
        return normalizeMediaUrl(candidate);
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("\\u003F", "?")
                .replace("\\u003f", "?")
                .replace("&amp;", "&");
    }

    private static int priority(String flag, String url) {
        if (isHls(url)) return 0;
        if (containsDirectMedia(url)) return 1;
        if (isPlayerLine(flag)) return 2;
        return 3;
    }

    private static boolean containsDirectMedia(String value) {
        if (isDirectMedia(value)) return true;
        if (value == null || value.isEmpty()) return false;
        for (String episode : value.split("#", -1)) {
            int separator = episode.indexOf('$');
            if (separator >= 0 && separator < episode.length() - 1
                    && isDirectMedia(episode.substring(separator + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHls(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static boolean isDirectMedia(String value) {
        if (!isHttpUrl(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv")
                || lower.contains(".mkv") || lower.contains(".webm") || lower.contains(".mov")
                || lower.contains(".ts?") || lower.endsWith(".ts");
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isPlayerLine(String flag) {
        String value = flag == null ? "" : flag.toLowerCase(Locale.ROOT).replace(" ", "");
        return value.contains("hxplayer") || value.contains("2mplayer") || value.contains("1mplayer");
    }

    private static String[] split(String value) {
        if (value == null || value.isEmpty()) return new String[0];
        return value.split("\\$\\$\\$", -1);
    }

    private static String joinGroups(List<PlayGroup> groups, Field field) {
        StringBuilder result = new StringBuilder();
        for (PlayGroup group : groups) {
            if (result.length() > 0) result.append("$$$");
            switch (field) {
                case FROM:
                    result.append(group.flag);
                    break;
                case URL:
                    result.append(group.url);
                    break;
                case SERVER:
                    result.append(group.server);
                    break;
                case NOTE:
                    result.append(group.note);
                    break;
            }
        }
        return result.toString();
    }

    private static String stringValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private enum Field { FROM, URL, SERVER, NOTE }

    private static final class PlayGroup {
        final String flag;
        final String url;
        final String server;
        final String note;
        final int priority;
        final int originalIndex;

        PlayGroup(String flag, String url, String server, String note, int priority, int originalIndex) {
            this.flag = flag;
            this.url = url;
            this.server = server;
            this.note = note;
            this.priority = priority;
            this.originalIndex = originalIndex;
        }
    }
}
