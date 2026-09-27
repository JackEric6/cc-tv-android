package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** MMOV 网页适配器，输出 TVBox CMS JSON 并从播放页提取页面公开的媒体地址。 */
final class MmovRequestProcess implements RequestProcess {
    private static final String PREFIX = "/mmov";
    private static final String SOURCE_PARAM = "source";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36";
    private static final Pattern VIDEO_SOURCE = Pattern.compile(
            "(?is)\\bvideoSrc\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]");

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
            if (!isAllowedSource(source)) return jsonResponse(error("source 参数必须是 mmov.app 地址"));
            String play = value(params, "play");
            if (!play.isEmpty()) return jsonResponse(play(source, play, value(params, "flag")));
            String ids = value(params, "ids");
            String query = value(params, "wd");
            String type = value(params, "t");
            if (!ids.isEmpty()) return jsonResponse(detail(source, ids));
            if (!query.isEmpty()) return jsonResponse(search(source, query, value(params, "pg")));
            if (!type.isEmpty()) return jsonResponse(category(source, type, value(params, "pg")));
            return jsonResponse(home(source, value(params, "pg")));
        } catch (Throwable error) {
            return jsonResponse(error("MMOV 适配失败: " + safeMessage(error)));
        }
    }

    private String home(String source, String page) throws IOException {
        int current = parsePage(page);
        String path = current <= 1 ? "/" : "/?page=" + current;
        Document document = Jsoup.parse(fetch(buildUrl(source, path)), source);
        JsonArray list = parseCards(document, source);
        JsonObject root = pageJson(list, current, hasNextPage(document));
        JsonArray classes = new JsonArray();
        addClass(classes, "1", "电影");
        addClass(classes, "2", "连续剧");
        addClass(classes, "3", "综艺");
        addClass(classes, "4", "动漫");
        root.add("class", classes);
        return root.toString();
    }

    private String category(String source, String type, String page) throws IOException {
        int current = parsePage(page);
        String path = "/type/" + encodePath(type) + ".html";
        if (current > 1) path += "?page=" + current;
        Document document = Jsoup.parse(fetch(buildUrl(source, path)), source);
        return pageJson(parseCards(document, source), current, hasNextPage(document)).toString();
    }

    private String search(String source, String query, String page) throws IOException {
        int current = parsePage(page);
        String path = "/vodsearch/-------------.html?wd=" + encodePath(query);
        if (current > 1) path += "&page=" + current;
        Document document = Jsoup.parse(fetch(buildUrl(source, path)), source);
        return pageJson(parseCards(document, source), current, hasNextPage(document)).toString();
    }

    private String detail(String source, String id) throws IOException {
        String cleanId = id.split(",", 2)[0].trim();
        if (cleanId.contains("/") || cleanId.contains("..")) return error("无效的视频 ID");
        String pageUrl = buildUrl(source, "/vod/" + cleanId + ".html");
        Document document = Jsoup.parse(fetch(pageUrl), pageUrl);
        Element detail = document.selectFirst(".stui-content__detail");
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", cleanId);
        vod.addProperty("vod_name", text(document.selectFirst(".stui-content__detail h3.title")));
        Element image = document.selectFirst(".stui-content__thumb img, .stui-content__thumb a img");
        vod.addProperty("vod_pic", image == null ? "" : firstNonEmpty(
                image.attr("data-src"), image.attr("src")));
        vod.addProperty("vod_content", attr(document.selectFirst("meta[name=description]"), "content"));
        vod.addProperty("vod_remarks", "");
        if (detail != null) {
            for (Element row : detail.select("p.data")) {
                String label = row.ownText().trim();
                if (label.isEmpty() && row.selectFirst("span") != null) label = row.selectFirst("span").text();
                String value = row.text().replaceFirst("^[^：:]*[：:]\\s*", "").trim();
                if (label.contains("主演")) vod.addProperty("vod_actor", value);
                else if (label.contains("导演")) vod.addProperty("vod_director", value);
                else if (label.contains("年份")) vod.addProperty("vod_year", value);
                else if (label.contains("地区")) vod.addProperty("vod_area", value);
                else if (label.contains("类型")) vod.addProperty("type_name", value);
            }
        }
        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (Element panel : document.select(".stui-pannel")) {
            Element heading = panel.selectFirst(".stui-pannel__head h3.title");
            Element first = panel.selectFirst(".stui-content__playlist a[href]");
            if (heading == null || first == null) continue;
            if (from.length() > 0) {
                from.append("$$$");
                urls.append("$$$");
            }
            from.append(heading.text().trim());
            boolean firstEpisode = true;
            for (Element episode : panel.select(".stui-content__playlist a[href]")) {
                String episodeUrl = resolve(source, episode.attr("href"));
                if (episodeUrl.isEmpty()) continue;
                if (!firstEpisode) urls.append('#');
                urls.append(episode.text().trim()).append('$').append(episodeUrl);
                firstEpisode = false;
            }
            if (firstEpisode) {
                int separator = from.lastIndexOf("$$$");
                from.setLength(separator < 0 ? 0 : separator);
            }
        }
        vod.addProperty("vod_play_from", from.toString());
        vod.addProperty("vod_play_url", urls.toString());
        JsonArray list = new JsonArray();
        list.add(vod);
        return pageJson(list, 1, false).toString();
    }

    private String play(String source, String target, String flag) throws IOException {
        String url = resolve(source, target);
        if (url.isEmpty() || !isAllowedSource(url)) return playResult("", flag);
        Document page = Jsoup.parse(fetch(url), url);
        String mediaUrl = extractVideoUrl(page.html());
        if (!mediaUrl.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("parse", 0);
            result.addProperty("jx", 0);
            result.addProperty("url", mediaUrl);
            result.addProperty("playUrl", "");
            result.addProperty("flag", flag == null ? "" : flag);
            JsonObject header = new JsonObject();
            header.addProperty("Referer", url);
            result.add("header", header);
            return result.toString();
        }
        return playResult(url, flag);
    }

    static String extractVideoUrl(String html) {
        if (html == null) return "";
        Matcher matcher = VIDEO_SOURCE.matcher(html.replace("\\/", "/").replace("&amp;", "&"));
        if (!matcher.find()) return "";
        String url = matcher.group(1).trim();
        return url.startsWith("https://") || url.startsWith("http://") ? url : "";
    }

    private String playResult(String url, String flag) {
        JsonObject result = new JsonObject();
        result.addProperty("parse", 1);
        result.addProperty("jx", 0);
        result.addProperty("url", url == null ? "" : url);
        result.addProperty("playUrl", "");
        result.addProperty("flag", flag == null ? "" : flag);
        return result.toString();
    }

    private JsonArray parseCards(Document document, String source) {
        JsonArray list = new JsonArray();
        for (Element item : document.select(".stui-vodlist__item")) {
            Element link = item.selectFirst("a.stui-vodlist__thumb[href], a[href*=/vod/]");
            if (link == null) continue;
            String id = idFromPath(link.attr("href"));
            if (id.isEmpty()) continue;
            Element title = item.selectFirst(".stui-vodlist__title a, h4 a");
            Element image = link.selectFirst("img");
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", id);
            vod.addProperty("vod_name", title == null ? firstNonEmpty(link.attr("title"), text(image)) : title.text().trim());
            vod.addProperty("vod_pic", image == null ? "" : firstNonEmpty(image.attr("data-src"), image.attr("data-original"), image.attr("src")));
            vod.addProperty("vod_remarks", text(item.selectFirst(".pic-text")));
            vod.addProperty("vod_play_from", "");
            vod.addProperty("vod_play_url", "");
            list.add(vod);
        }
        return list;
    }

    private JsonObject pageJson(JsonArray list, int page, boolean hasNext) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", page);
        root.addProperty("pagecount", hasNext ? page + 1 : page);
        root.addProperty("limit", 30);
        root.addProperty("total", list.size());
        root.add("list", list);
        return root;
    }

    private boolean hasNextPage(Document document) {
        return document.select("a[href]").stream().anyMatch(link ->
                link.text().contains("下一页") || link.text().contains("下页"));
    }

    private void addClass(JsonArray classes, String id, String name) {
        JsonObject item = new JsonObject();
        item.addProperty("type_id", id);
        item.addProperty("type_name", name);
        classes.add(item);
    }

    private String fetch(String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Referer", origin(url)).build();
        try (Response response = OkHttp.client(20000).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return response.body().string();
        }
    }

    private String buildUrl(String source, String path) {
        return HttpUrl.get(source).newBuilder().encodedPath(path).build().toString();
    }

    private String resolve(String source, String path) {
        try {
            HttpUrl base = HttpUrl.get(source);
            HttpUrl result = base.resolve(path);
            return result == null ? "" : result.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String origin(String url) {
        HttpUrl parsed = HttpUrl.get(url);
        return parsed.scheme() + "://" + parsed.host() + "/";
    }

    private boolean isAllowedSource(String source) {
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            return host.equals("mmov.app") || host.endsWith(".mmov.app");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String idFromPath(String path) {
        Matcher matcher = Pattern.compile("/vod/(\\d+)\\.html").matcher(path == null ? "" : path);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String encodePath(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    private int parsePage(String page) {
        try { return Math.max(1, Integer.parseInt(page)); } catch (Throwable ignored) { return 1; }
    }

    private String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private String text(Element element) { return element == null ? "" : element.text().trim(); }

    private String attr(Element element, String key) { return element == null ? "" : element.attr(key).trim(); }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private String error(String message) {
        JsonObject root = pageJson(new JsonArray(), 1, false);
        root.addProperty("msg", message);
        return root.toString();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private NanoHTTPD.Response jsonResponse(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
