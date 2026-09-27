package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** KKT影视 CMS 本地适配接口。配置源请求先到本机,再转发并整理 KKT响应。 */
final class KktvsRequestProcess implements RequestProcess {
    private static final String PREFIX = "/kktvs";
    private static final String SOURCE_PARAM = "source";
    private static final String PLAY_PARAM = "play";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final Pattern DETAIL_ID = Pattern.compile("/detail/(\\d+)\\.html");

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
                return jsonResponse(error("source 参数必须是 kktvs.com 地址"));
            }
            if (!TextUtils.isEmpty(value(params, PLAY_PARAM))) {
                return jsonResponse(play(params));
            }
            if (isHomeRequest(params)) {
                return jsonResponse(home(source));
            }
            return jsonResponse(KktvsCmsParser.normalize(fetch(buildUpstreamUrl(source, params))));
        } catch (Throwable th) {
            return jsonResponse(error("KKT影视适配失败: " + safeMessage(th)));
        }
    }

    private String home(String source) throws IOException {
        String pageUrl = origin(source);
        Document document = Jsoup.parse(fetch(pageUrl), pageUrl);
        List<String> ids = new ArrayList<>();
        // KKT 的 ids 接口实际最多稳定返回 20 条;各取 10 条,既保留两类月榜,又避免首页请求过大。
        appendRankIds(document, "电视剧", ids, 10);
        appendRankIds(document, "电影", ids, 10);
        if (ids.isEmpty()) {
            return KktvsCmsParser.normalize(fetch(buildUpstreamUrl(source, "ac", "detail", "pg", "1")));
        }

        String detail = fetch(buildUpstreamUrl(source, "ac", "detail", "ids", join(ids)));
        JsonObject root = KktvsCmsParser.parseObject(detail);
        if (root == null) return KktvsCmsParser.normalize(detail);
        KktvsCmsParser.reorderList(root, ids);
        addHomeClasses(root);
        return KktvsCmsParser.normalize(root.toString());
    }

    /** 播放解析入口:保留 hxplayer/2mplayer 线路,把播放器页中的真实媒体地址交给播放器。 */
    private String play(Map<String, String> params) throws IOException {
        String target = value(params, PLAY_PARAM);
        target = selectEpisodeUrl(target);
        if (target.isEmpty()) return playResult("", false, value(params, "flag"));

        String mediaUrl = KktvsCmsParser.directMediaUrl(target);
        if (mediaUrl.isEmpty()) {
            String body = fetch(target);
            mediaUrl = KktvsCmsParser.extractMediaUrl(body, target);
        }
        if (!mediaUrl.isEmpty()) return playResult(mediaUrl, true, value(params, "flag"));

        // 页面结构变化时不丢线路,交给应用内 WebView 嗅探兜底。
        return playResult(target, false, value(params, "flag"));
    }

    private String playResult(String url, boolean direct, String flag) {
        JsonObject result = new JsonObject();
        result.addProperty("parse", direct ? 0 : 1);
        result.addProperty("jx", 0);
        result.addProperty("url", url == null ? "" : url);
        result.addProperty("playUrl", "");
        result.addProperty("flag", flag == null ? "" : flag);
        return result.toString();
    }

    private String selectEpisodeUrl(String value) {
        String target = value == null ? "" : value.trim();
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        int dollar = target.indexOf('$');
        if (dollar >= 0 && dollar + 1 < target.length()) target = target.substring(dollar + 1);
        if (target.startsWith("//")) target = "https:" + target;
        return target.trim();
    }

    private void appendRankIds(Document document, String category, List<String> ids, int limit) {
        if (document == null) return;
        int added = 0;
        for (Element block : document.select("div.module-side")) {
            Element titleElement = block.selectFirst(".module-heading .module-title");
            String title = titleElement == null ? "" : titleElement.text();
            if (!title.contains(category) || !title.contains("月榜")) continue;
            for (Element link : block.select("a.text-list-item[href*=/detail/]") ) {
                Matcher matcher = DETAIL_ID.matcher(link.attr("href"));
                if (matcher.find() && !ids.contains(matcher.group(1))) {
                    ids.add(matcher.group(1));
                    if (++added >= limit) return;
                }
            }
        }
    }

    private void addHomeClasses(JsonObject root) {
        JsonArray classes = new JsonArray();
        JsonObject tv = new JsonObject();
        tv.addProperty("type_id", "1");
        tv.addProperty("type_name", "电视剧");
        classes.add(tv);
        JsonObject movie = new JsonObject();
        movie.addProperty("type_id", "2");
        movie.addProperty("type_name", "电影");
        classes.add(movie);
        root.add("class", classes);
    }

    private boolean isHomeRequest(Map<String, String> params) {
        return TextUtils.isEmpty(value(params, "ac"))
                && TextUtils.isEmpty(value(params, "wd"))
                && TextUtils.isEmpty(value(params, "ids"))
                && TextUtils.isEmpty(value(params, "t"));
    }

    private String buildUpstreamUrl(String source, Map<String, String> params) {
        HttpUrl.Builder builder = HttpUrl.get(source).newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (SOURCE_PARAM.equals(entry.getKey()) || entry.getValue() == null) continue;
                builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        return builder.build().toString();
    }

    private String buildUpstreamUrl(String source, String... query) {
        HttpUrl.Builder builder = HttpUrl.get(source).newBuilder();
        for (int i = 0; i + 1 < query.length; i += 2) {
            builder.addQueryParameter(query[i], query[i + 1]);
        }
        return builder.build().toString();
    }

    private String origin(String source) {
        HttpUrl url = HttpUrl.get(source);
        StringBuilder result = new StringBuilder(url.scheme()).append("://").append(url.host());
        if (url.port() != HttpUrl.defaultPort(url.scheme())) result.append(':').append(url.port());
        return result.append('/').toString();
    }

    private String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Referer", origin(url))
                .build();
        try (Response response = OkHttp.client(20000).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return response.body().string();
        }
    }

    private boolean isAllowedSource(String source) {
        if (TextUtils.isEmpty(source)) return false;
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            return host.equals("kktvs.com") || host.endsWith(".kktvs.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private String error(String message) {
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

    private String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
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
