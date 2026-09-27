package com.github.tvbox.osc.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 爱看机器人响应的纯解析逻辑,与本机 HTTP 路由分离。 */
final class IkanbotCmsParser {
    private IkanbotCmsParser() {
    }

    static String buildToken(String videoId, String eToken) {
        if (videoId == null || eToken == null || videoId.length() < 4 || eToken.length() < 8) {
            return "";
        }
        String digits = videoId.substring(videoId.length() - 4);
        String rest = eToken;
        StringBuilder token = new StringBuilder();
        try {
            for (int i = 0; i < digits.length(); i++) {
                int offset = Character.digit(digits.charAt(i), 10) % 3 + 1;
                if (offset + 8 > rest.length()) return "";
                token.append(rest, offset, offset + 8);
                rest = rest.substring(offset + 8);
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return token.toString();
    }

    static List<PlaySource> parsePlaySources(String response) {
        List<PlaySource> sources = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) return sources;
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            if (!root.has("state") || root.get("state").getAsInt() != 1) return sources;
            JsonObject data = root.getAsJsonObject("data");
            if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return sources;
            JsonArray list = data.getAsJsonArray("list");
            for (int i = 0; i < list.size(); i++) {
                JsonObject line = list.get(i).isJsonObject() ? list.get(i).getAsJsonObject() : null;
                if (line == null || !line.has("resData")) continue;
                String resData = line.get("resData").getAsString();
                JsonElement parsed = JsonParser.parseString(resData);
                if (!parsed.isJsonArray()) continue;
                List<PlayItem> episodes = new ArrayList<>();
                for (JsonElement item : parsed.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonObject resource = item.getAsJsonObject();
                    String flag = stringValue(resource, "flag");
                    String urls = stringValue(resource, "url");
                    if (urls.isEmpty()) continue;
                    for (String entry : urls.split("#")) {
                        int separator = entry.indexOf('$');
                        if (separator <= 0 || separator >= entry.length() - 1) continue;
                        String name = entry.substring(0, separator).trim();
                        String url = entry.substring(separator + 1).trim();
                        if (!isHlsUrl(url)) continue;
                        episodes.add(new PlayItem(name, url));
                    }
                    if (!episodes.isEmpty()) {
                        sources.add(new PlaySource(flag.isEmpty() ? "线路" + (i + 1) : flag, episodes));
                    }
                }
            }
        } catch (Throwable ignored) {
            sources.clear();
        }
        return sources;
    }

    private static boolean isHlsUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                ? lower.contains(".m3u8") : false;
    }

    private static String stringValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try {
            return object.get(name).getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static final class PlaySource {
        final String name;
        final List<PlayItem> episodes;

        PlaySource(String name, List<PlayItem> episodes) {
            this.name = name;
            this.episodes = episodes;
        }
    }

    static final class PlayItem {
        final String name;
        final String url;

        PlayItem(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
