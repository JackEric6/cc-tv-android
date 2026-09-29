package com.github.tvbox.osc.server;

import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 荐片运行时 API 域名发现与故障切换的纯逻辑部分。 */
final class JianpianOriginResolver {
    static final String DISCOVERY_URL =
            "https://ssopj-1462720388.cos.accelerate.myqcloud.com/config.txt";
    // 5.0.60 官方 APK 的恢复配置还包含 hodpj.cc；旧三域名目前经常返回
    // 404 或无法建立 TLS，漏掉该域名会让搜索在所有候选上直接失败。
    static final String DEFAULT_SERVERS = "bdgnbrws.com,fvevfbr.com,swgsdfew.com,hodpj.cc";

    private JianpianOriginResolver() {
    }

    static List<String> parseDiscovery(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value != null) {
            String[] tokens = value.split("[,\\r\\n\\t ]+");
            for (String token : tokens) {
                String origin = normalize(token);
                if (origin != null) result.add(origin);
            }
        }
        return new ArrayList<>(result);
    }

    static List<String> candidates(String preferred, String discovered) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        add(result, preferred);
        result.addAll(parseDiscovery(discovered));
        for (String server : DEFAULT_SERVERS.split(",")) add(result, "https://" + server);
        add(result, JianpianCrypto.BASE_URL);
        // 官网会把旧入口跳转到 jianpian25.com；保留它只作为最后的兼容探测，
        // 这样配置切换后不会因为旧缓存而永远停在失效入口。
        add(result, "https://jianpian25.com");
        return new ArrayList<>(result);
    }

    static boolean shouldFailover(int statusCode) {
        return statusCode == 404 || statusCode == 408 || statusCode == 425
                || statusCode == 429 || statusCode >= 500;
    }

    static String normalize(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        if ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (!text.matches("(?i)^https?://.*")) text = "https://" + text;
        try {
            HttpUrl url = HttpUrl.get(text);
            if (!"https".equalsIgnoreCase(url.scheme()) || url.host().isEmpty()) return null;
            if (url.encodedPath().length() > 1 || url.query() != null || url.fragment() != null) {
                return null;
            }
            String host = url.host().toLowerCase(Locale.ROOT);
            return "https://" + host + (url.port() == 443 ? "" : ":" + url.port());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void add(LinkedHashSet<String> result, String value) {
        String normalized = normalize(value);
        if (normalized != null) result.add(normalized);
    }
}
