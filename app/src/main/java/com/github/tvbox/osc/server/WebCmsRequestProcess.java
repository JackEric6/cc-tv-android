package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 网页型影视站适配器。
 *
 * <p>不少资源站没有开放稳定的 CMS JSON 接口，但搜索页、详情页和播放页仍遵循
 * MacCMS/FED 等常见模板。这里把页面转换为 TVBox CMS JSON，播放地址保留为播放页，
 * 交给现有 WebView 嗅探链路处理，避免把第三方播放页的临时签名硬编码进 APK。</p>
 */
final class WebCmsRequestProcess implements RequestProcess {
    private static final String PREFIX = "/webcms";
    private static final String SOURCE_PARAM = "source";
    private static final String PROFILE_PARAM = "profile";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                    + "Chrome/120 Mobile Safari/537.36";
    private static final int MAX_RESULTS = 20;
    private static final int MAX_EPISODES = 120;
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "old.sliiic.org",
            "www.0451yccz.com",
            "www.hmjy100.com",
            "www.wljingyan.com",
            "www.kdndt.net",
            "www.knshuichuli.com",
            "m.syshuoyuan.com",
            "shanghai.365che.net",
            "www.nmgflw.com",
            "www.chgxdq.com",
            "yz.sdhfcj.com",
            "www.sczxsx.com",
            "www.nbhaierxin.com",
            "www.zzhuilv.cn",
            "m.sjtx-oa.com",
            "www.lanyajc.com",
            "www.ikang.org",
            "www.fjsycjs.com",
            "www.zanpian.org",
            "www.88ystv.com"
    ));
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(18, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build();

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
                return jsonResponse(error("网页源地址不是已适配站点"));
            }
            String profile = value(params, PROFILE_PARAM);
            if (profile.isEmpty()) profile = profile(source);
            String ids = value(params, "ids");
            String wd = value(params, "wd");
            // 网页源只参与搜索和详情播放，不接管首页/分类请求。
            if (!isSearchOrDetailRequest(params)) {
                return jsonResponse(pageJson(new JsonArray(), 1));
            }
            String body;
            if (!ids.isEmpty()) {
                body = detail(source, profile, ids);
            } else if (!wd.isEmpty()) {
                body = search(source, profile, wd, value(params, "pg"));
            } else {
                body = pageJson(new JsonArray(), 1);
            }
            return jsonResponse(body);
        } catch (Throwable th) {
            return jsonResponse(error("网页影视源适配失败: " + safeMessage(th)));
        }
    }

    static boolean isSearchOrDetailRequest(Map<String, String> params) {
        return !valueOf(params, "ids").isEmpty() || !valueOf(params, "wd").isEmpty();
    }

    private String search(String source, String profile, String keyword, String page) throws IOException {
        String encoded = encode(keyword);
        int currentPage = parsePage(page);
        String[] paths;
        if ("fed".equals(profile)) {
            paths = new String[]{
                    buildPath("/search.php", "searchword", keyword, "page", String.valueOf(currentPage)),
                    buildPath("/search.php", "wd", keyword, "page", String.valueOf(currentPage))
            };
        } else if ("zanpian".equals(profile)) {
            paths = new String[]{
                    "/vod-search-wd-" + encoded + "-p-" + currentPage + ".html",
                    "/vod-search-wd-" + encoded + "-p-1.html"
            };
        } else {
            paths = new String[]{
                    buildPath("/vodsearch/-------------.html", "wd", keyword, "pg", String.valueOf(currentPage)),
                    "/vodsearch/" + encoded + "-------------.html",
                    buildPath("/search.php", "searchword", keyword, "page", String.valueOf(currentPage))
            };
        }
        IOException last = null;
        for (String path : paths) {
            try {
                JsonArray list = parseCards(fetchDocument(source, path), profile);
                if (list.size() > 0) return pageJson(list, currentPage);
            } catch (IOException error) {
                last = error;
            }
        }
        if (last != null) throw last;
        return pageJson(new JsonArray(), currentPage);
    }

    private String detail(String source, String profile, String ids) throws IOException {
        String id = ids.split(",", 2)[0].trim();
        String detailUrl = resolve(source, id);
        Document document = fetchDocument(detailUrl);
        JsonObject vod = new JsonObject();
        String name = firstText(document, "h1", ".fed-deta-info h1", ".con_name h1",
                ".detail-info h1", "meta[property=og:title]");
        if (name.isEmpty()) name = document.title();
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", cleanTitle(name));
        vod.addProperty("vod_pic", firstImage(document));
        vod.addProperty("vod_year", firstYear(document.text()));
        vod.addProperty("vod_content", firstText(document, "meta[name=description]",
                ".fed-deta-content", ".yplx_c3", ".detail-content", ".vod_content"));
        vod.addProperty("vod_remarks", "网页线路");

        LinkedHashSet<String> links = new LinkedHashSet<>();
        Elements playable = document.select("a[href]");
        for (Element link : playable) {
            String href = link.attr("href").trim();
            if (!isPlayablePage(href)) continue;
            String absolute = resolve(detailUrl, href);
            if (!absolute.equals(detailUrl)) links.add(episode(link, absolute));
            if (links.size() >= MAX_EPISODES) break;
        }
        if (links.isEmpty()) {
            String direct = firstMediaUrl(document.html());
            if (!direct.isEmpty()) links.add("正片$" + direct);
        }
        StringBuilder playUrls = new StringBuilder();
        for (String link : links) {
            if (playUrls.length() > 0) playUrls.append('#');
            playUrls.append(link);
        }
        vod.addProperty("vod_play_from", links.isEmpty() ? "" : "网页线路");
        vod.addProperty("vod_play_url", playUrls.toString());

        JsonArray list = new JsonArray();
        list.add(vod);
        return pageJson(list, 1);
    }

    private JsonArray parseCards(Document document, String profile) {
        JsonArray result = new JsonArray();
        if (document == null) return result;
        Set<String> seen = new HashSet<>();
        Elements candidates;
        if ("fed".equals(profile)) {
            candidates = document.select("a.fed-list-title[href]");
            if (candidates.isEmpty()) candidates = document.select("a[href*=/content]");
        } else if ("zanpian".equals(profile)) {
            candidates = document.select("a.play-img[href], a[href*=/Movies/], a[href*=/TVseries/], "
                    + "a[href*=/Animation/], a[href*=/Arts/]");
        } else {
            candidates = document.select("a[href^=/v/], a[href*='/v/'], a[href*='/vod/'], "
                    + "a[href*='/detail/'], .stui-vodlist li a, .module-item a");
        }
        for (Element link : candidates) {
            String href = link.attr("href").trim();
            if (!isDetailPage(href) || !seen.add(href)) continue;
            String name = attrOrText(link, "title");
            if (name.isEmpty()) name = textOf(link);
            if (name.isEmpty()) continue;
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", href);
            vod.addProperty("vod_name", cleanTitle(name));
            vod.addProperty("vod_pic", imageOf(link));
            vod.addProperty("vod_remarks", remarkOf(link));
            vod.addProperty("vod_year", firstYear(name));
            vod.addProperty("vod_play_from", "");
            vod.addProperty("vod_play_url", "");
            result.add(vod);
            if (result.size() >= MAX_RESULTS) break;
        }
        return result;
    }

    private boolean isDetailPage(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.startsWith("/") && !lower.startsWith("//")
                && (lower.contains("/v/") || lower.contains("/content")
                || lower.contains("/detail") || lower.contains("/movie")
                || lower.contains("/tvseries") || lower.contains("/animation")
                || lower.contains("/arts"));
    }

    private boolean isPlayablePage(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.contains("/p/") || lower.contains("/play")
                || lower.contains("/vodplay") || lower.contains("/player")
                || lower.endsWith(".m3u8") || lower.endsWith(".mp4");
    }

    private String episode(Element link, String absolute) {
        String title = attrOrText(link, "title");
        if (title.isEmpty()) title = textOf(link);
        if (title.isEmpty()) title = "正片";
        return cleanEpisode(title) + "$" + absolute;
    }

    private Document fetchDocument(String source, String path) throws IOException {
        return fetchDocument(resolve(source, path));
    }

    private Document fetchDocument(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return Jsoup.parse(response.body().string(), url);
        }
    }

    private String resolve(String base, String path) {
        if (path == null || path.isEmpty()) return base;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return HttpUrl.get(base).resolve(path).toString();
    }

    private String buildPath(String path, String key, String value, String pageKey, String page) {
        return path + "?" + key + "=" + encode(value) + "&" + pageKey + "=" + encode(page);
    }

    private String pageJson(JsonArray list, int page) {
        JsonObject root = new JsonObject();
        root.addProperty("code", list.size() > 0 ? 1 : 0);
        root.addProperty("msg", list.size() > 0 ? "数据列表" : "暂无搜索结果");
        root.addProperty("page", page);
        root.addProperty("pagecount", page);
        root.addProperty("limit", 20);
        root.addProperty("total", list.size());
        root.add("list", list);
        return root.toString();
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

    private String profile(String source) {
        String host = HttpUrl.get(source).host().toLowerCase(Locale.ROOT);
        if (host.equals("www.zzhuilv.cn")) return "fed";
        if (host.equals("www.zanpian.org")) return "zanpian";
        return "maccms";
    }

    private boolean isAllowedSource(String source) {
        if (TextUtils.isEmpty(source)) return false;
        try {
            return ALLOWED_HOSTS.contains(HttpUrl.get(source).host().toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String firstText(Document document, String... selectors) {
        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element == null) continue;
            String value = "meta".equals(element.tagName())
                    ? element.attr("content") : element.text();
            if (!TextUtils.isEmpty(value)) return value.trim();
        }
        return "";
    }

    private String firstImage(Document document) {
        Element image = document.selectFirst(".detail-info-pic img, .fed-deta-info img, "
                + "meta[property=og:image], img[alt]");
        if (image == null) return "";
        if ("meta".equals(image.tagName())) return image.attr("content").trim();
        return imageAttr(image);
    }

    private String imageOf(Element link) {
        Element image = link.selectFirst("img");
        return image == null ? "" : imageAttr(image);
    }

    private String imageAttr(Element image) {
        for (String key : new String[]{"data-original", "data-src", "data-lazy", "src"}) {
            String value = image.attr(key).trim();
            if (!value.isEmpty() && !value.contains("blank")) return value;
        }
        return "";
    }

    private String remarkOf(Element link) {
        Element parent = link.parent();
        return parent == null ? "" : parent.text().trim();
    }

    private String attrOrText(Element element, String attr) {
        return element.attr(attr).trim();
    }

    private String textOf(Element element) {
        return element.text().trim().replaceAll("\\s+", " ");
    }

    private String firstMediaUrl(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "https?[^\"'\\\\ ]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\\\ ]*)?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? matcher.group().replace("\\/", "/") : "";
    }

    private String cleanTitle(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String cleanEpisode(String value) {
        return cleanTitle(value).replace("$", "");
    }

    private String firstYear(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}")
                .matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group() : "";
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private int parsePage(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private String value(Map<String, String> params, String key) {
        return valueOf(params, key);
    }

    private static String valueOf(Map<String, String> params, String key) {
        if (params == null) return "";
        String value = params.get(key);
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private NanoHTTPD.Response jsonResponse(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8",
                body == null ? "{}" : body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
