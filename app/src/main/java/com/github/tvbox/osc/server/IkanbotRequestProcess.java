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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** 把爱看机器人 HTML 页面转换为 TVBox HTTP/CMS JSON。 */
final class IkanbotRequestProcess implements RequestProcess {
    private static final String PREFIX = "/ikanbot";
    private static final String SOURCE_PARAM = "source";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?:19|20)\\d{2}");

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && (PREFIX.equals(fileName) || fileName.startsWith(PREFIX + "/"));
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        try {
            if (params == null) params = session.getParms();
            String source = params == null ? "" : params.get(SOURCE_PARAM);
            if (!isAllowedSource(source)) {
                return jsonResponse(error("source 参数必须是 ikanbot.com 地址"));
            }
            String ids = value(params, "ids");
            String wd = value(params, "wd");
            String type = value(params, "t");
            String body;
            if (!ids.isEmpty()) {
                body = detail(source, ids);
            } else if (!wd.isEmpty()) {
                body = search(source, wd);
            } else if (!type.isEmpty()) {
                body = category(source, type, value(params, "pg"));
            } else {
                body = home(source);
            }
            return jsonResponse(body);
        } catch (Throwable th) {
            return jsonResponse(error("爱看机器人适配失败: " + safeMessage(th)));
        }
    }

    private String home(String source) throws IOException {
        Document document = Jsoup.parse(fetch(buildUrl(source, "/")), source);
        JsonArray list = new JsonArray();
        for (Element item : document.select(".v-list .item a[href^=/play/]") ) {
            JsonObject vod = parseCard(item, "");
            if (vod != null) list.add(vod);
        }
        return sortJson(list);
    }

    private String category(String source, String type, String page) throws IOException {
        int currentPage = parsePage(page);
        String pageSuffix = currentPage <= 1 ? "" : "-p-" + currentPage;
        String path = "1".equals(type)
                ? "/hot/index-movie-%E7%83%AD%E9%97%A8" + pageSuffix + ".html"
                : "/hot/index-tv-%E7%83%AD%E9%97%A8" + pageSuffix + ".html";
        Document document = Jsoup.parse(fetch(buildUrl(source, path)), source);
        JsonArray list = new JsonArray();
        for (Element item : document.select(".v-list .item a[href^=/play/]") ) {
            JsonObject vod = parseCard(item, String.valueOf(currentPage));
            if (vod != null) list.add(vod);
        }
        return pageJson(list, currentPage, hasNextPage(document));
    }

    private String search(String source, String query) throws IOException {
        Document document = Jsoup.parse(fetch(buildUrl(source, "/search", "q", query)), source);
        JsonArray list = new JsonArray();
        for (Element item : document.select("div.media") ) {
            Element link = item.selectFirst("a.cover-link[href^=/play/]");
            if (link == null) continue;
            JsonObject vod = parseSearchCard(link, item);
            if (vod != null) list.add(vod);
        }
        return pageJson(list, "1");
    }

    private String detail(String source, String ids) throws IOException {
        String id = ids.split(",", 2)[0].trim();
        Document document = Jsoup.parse(fetch(buildUrl(source, "/play/" + id)), source);
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", text(document.selectFirst("#video_title")));
        vod.addProperty("vod_pic", attr(document.selectFirst("#playList img.cover"), "data-src"));

        Elements metas = document.select("#playList .detail .meta");
        if (metas.size() > 1) vod.addProperty("vod_sub", metas.get(1).text());
        if (metas.size() > 2) vod.addProperty("vod_year", firstYear(metas.get(2).text()));
        if (metas.size() > 3) vod.addProperty("vod_area", metas.get(3).text());
        if (metas.size() > 4) vod.addProperty("vod_actor", metas.get(4).text());

        String description = attr(document.selectFirst("meta[name=description]"), "content");
        vod.addProperty("vod_content", description);
        vod.addProperty("vod_remarks", "爱看机器人线路");

        String eToken = attr(document.getElementById("e_token"), "value");
        String mtype = attr(document.getElementById("mtype"), "value");
        String token = IkanbotCmsParser.buildToken(id, eToken);
        List<IkanbotCmsParser.PlaySource> sources = token.isEmpty()
                ? new ArrayList<>()
                : IkanbotCmsParser.parsePlaySources(fetch(buildUrl(source, "/api/getResN",
                "videoId", id, "mtype", mtype, "token", token)));
        addPlayData(vod, sources);

        JsonArray list = new JsonArray();
        list.add(vod);
        return pageJson(list, "1");
    }

    private void addPlayData(JsonObject vod, List<IkanbotCmsParser.PlaySource> sources) {
        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (IkanbotCmsParser.PlaySource source : sources) {
            if (source.episodes.isEmpty()) continue;
            if (from.length() > 0) {
                from.append("$$$");
                urls.append("$$$");
            }
            from.append(source.name);
            for (int i = 0; i < source.episodes.size(); i++) {
                if (i > 0) urls.append('#');
                IkanbotCmsParser.PlayItem episode = source.episodes.get(i);
                urls.append(episode.name).append('$').append(episode.url);
            }
        }
        vod.addProperty("vod_play_from", from.toString());
        vod.addProperty("vod_play_url", urls.toString());
    }

    private JsonObject parseCard(Element link, String page) {
        String id = extractId(link.attr("href"));
        if (id.isEmpty()) return null;
        Element image = link.selectFirst("img");
        String title = text(link.selectFirst("p"));
        if (title.isEmpty()) title = attr(image, "alt");
        return vod(id, title, attr(image, "data-src"), "", page);
    }

    private JsonObject parseSearchCard(Element link, Element item) {
        String id = extractId(link.attr("href"));
        if (id.isEmpty()) return null;
        Element image = link.selectFirst("img");
        Elements meta = item.select(".media-body .small");
        JsonObject vod = vod(id, text(item.selectFirst(".title-text")), attr(image, "data-src"), "", "1");
        if (meta.size() > 0) vod.addProperty("vod_area", meta.get(0).text());
        if (meta.size() > 1) vod.addProperty("vod_actor", meta.get(1).text());
        return vod;
    }

    private JsonObject vod(String id, String name, String pic, String remarks, String page) {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", name);
        vod.addProperty("vod_pic", pic);
        vod.addProperty("vod_remarks", remarks);
        vod.addProperty("vod_year", firstYear(name));
        vod.addProperty("vod_play_from", "");
        vod.addProperty("vod_play_url", "");
        return vod;
    }

    private String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Referer", url)
                .build();
        try (Response response = OkHttp.client(20000).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private String buildUrl(String source, String path, String... query) {
        HttpUrl.Builder builder = HttpUrl.get(source).newBuilder().encodedPath(path);
        for (int i = 0; i + 1 < query.length; i += 2) {
            builder.addQueryParameter(query[i], query[i + 1]);
        }
        return builder.build().toString();
    }

    private boolean isAllowedSource(String source) {
        if (TextUtils.isEmpty(source)) return false;
        try {
            String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
            return host.equals("ikanbot.com") || host.endsWith(".ikanbot.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String sortJson(JsonArray list) {
        JsonObject root = new JsonObject();
        JsonArray classes = new JsonArray();
        JsonObject movie = new JsonObject();
        movie.addProperty("type_id", "1");
        movie.addProperty("type_name", "电影");
        classes.add(movie);
        JsonObject tv = new JsonObject();
        tv.addProperty("type_id", "2");
        tv.addProperty("type_name", "剧集");
        classes.add(tv);
        root.add("class", classes);
        root.add("list", list);
        return root.toString();
    }

    private String pageJson(JsonArray list, String page) {
        return pageJson(list, parsePage(page), false);
    }

    private String pageJson(JsonArray list, int page, boolean hasNextPage) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("page", page);
        root.addProperty("pagecount", hasNextPage ? page + 1 : page);
        root.addProperty("limit", 20);
        root.addProperty("total", list.size());
        root.add("list", list);
        return root.toString();
    }

    private boolean hasNextPage(Document document) {
        for (Element link : document.select(".page-more a")) {
            if ("下一页".equals(text(link))) return true;
        }
        return false;
    }

    private String error(String message) {
        JsonArray list = new JsonArray();
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", 1);
        root.addProperty("pagecount", 1);
        root.addProperty("limit", 20);
        root.addProperty("total", 0);
        root.add("list", list);
        return root.toString();
    }

    private int parsePage(String page) {
        try {
            return Math.max(1, Integer.parseInt(page));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private String extractId(String href) {
        if (href == null) return "";
        String value = href.trim();
        int start = value.indexOf("/play/");
        if (start < 0) return "";
        String id = value.substring(start + 6);
        int end = id.indexOf('/');
        if (end >= 0) id = id.substring(0, end);
        end = id.indexOf('?');
        if (end >= 0) id = id.substring(0, end);
        return id;
    }

    private String firstYear(String value) {
        if (value == null) return "";
        Matcher matcher = YEAR_PATTERN.matcher(value);
        String year = "";
        while (matcher.find()) year = matcher.group();
        return year;
    }

    private String value(Map<String, String> params, String key) {
        return params == null || params.get(key) == null ? "" : params.get(key).trim();
    }

    private String text(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private String attr(Element element, String name) {
        return element == null ? "" : element.attr(name).trim();
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
