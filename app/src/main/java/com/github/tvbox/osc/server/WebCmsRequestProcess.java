package com.github.tvbox.osc.server;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Dns;
import okhttp3.FormBody;
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
    private static final String TAG = "WebCmsRequest";
    private static final String PREFIX = "/webcms";
    private static final String SOURCE_PARAM = "source";
    private static final String PROFILE_PARAM = "profile";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                    + "Chrome/120 Mobile Safari/537.36";
    private static final int MAX_RESULTS = 20;
    private static final int MAX_EPISODES = 120;
    private static final int MAX_PLAYBACK_LINES = 8;
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "old.sliiic.org",
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
    private static final OkHttpClient DISCOVERY_CLIENT = CLIENT.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient SEARCH_CLIENT = CLIENT.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    /**
     * 搜索请求描述。部分站点的搜索入口不是 GET 页面，不能把所有站点强行拼成同一种 URL。
     */
    static final class SearchRequest {
        final String method;
        final String path;
        final Map<String, String> form;
        final boolean htmlInJson;

        SearchRequest(String method, String path, Map<String, String> form) {
            this(method, path, form, false);
        }

        SearchRequest(String method, String path, Map<String, String> form, boolean htmlInJson) {
            this.method = method;
            this.path = path;
            this.form = form == null ? Collections.<String, String>emptyMap() : form;
            this.htmlInJson = htmlInJson;
        }
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
        int currentPage = parsePage(page);
        List<SearchRequest> requests = new ArrayList<>();
        boolean searchDisabled = false;
        boolean searchBlocked = false;
        boolean discoverAfterKnownRoutes = "zanpian".equals(profile);
        if (!discoverAfterKnownRoutes) {
            try {
                requests.addAll(discoverSearchRequests(source, keyword, currentPage, profile));
            } catch (IOException error) {
                // 某些源拦截入口页，继续尝试该 CMS 的常见搜索路由。
                Log.w(TAG, "search-discovery host=" + sourceHost(source)
                        + " error=" + safeMessage(error));
                if (isBlockedSearchFailure(safeMessage(error))) searchBlocked = true;
            }
        }
        requests.addAll(buildSearchRequests(profile, keyword, currentPage));
        Set<String> attempted = new HashSet<>();
        for (int index = 0; ; index++) {
            if (index >= requests.size()) {
                if (!discoverAfterKnownRoutes) break;
                discoverAfterKnownRoutes = false;
                try {
                    requests.addAll(discoverSearchRequests(
                            source, keyword, currentPage, profile));
                } catch (IOException error) {
                    Log.w(TAG, "search-discovery host=" + sourceHost(source)
                            + " error=" + safeMessage(error));
                    if (isBlockedSearchFailure(safeMessage(error))) searchBlocked = true;
                }
                continue;
            }
            SearchRequest request = requests.get(index);
            String signature = request.method + " "
                    + resolve(source, request.path) + " " + request.form;
            if (!attempted.add(signature)) continue;
            try {
                Document document = fetchDocument(source, request);
                if (isSearchDisabledPage(document)) {
                    searchDisabled = true;
                    continue;
                }
                JsonArray list = parseCards(document, profile, keyword);
                if (list.size() > 0) return pageJson(list, currentPage);
                Log.i(TAG, "search-empty host=" + sourceHost(source)
                        + " evidence=" + hasSearchEvidence(document, keyword)
                        + " candidates=" + searchCandidates(document, profile, keyword).size());
            } catch (IOException error) {
                // 站点自身的 403、验证码、超时和错误路径都只能表示“本源暂无可用搜索结果”。
                // 不能把异常页面交给宽泛解析器，更不能用首页推荐伪造搜索结果。
                Log.w(TAG, "search-failed host=" + sourceHost(source)
                        + " error=" + safeMessage(error));
                if (isBlockedSearchFailure(safeMessage(error))) searchBlocked = true;
            }
        }
        if (searchDisabled) return error("该源站已关闭站内搜索", currentPage);
        if (searchBlocked) return error("源站触发安全验证或 HTTP 403，当前无法读取搜索结果", currentPage);
        return pageJson(new JsonArray(), currentPage);
    }

    static boolean isBlockedSearchFailure(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("站点安全验证或拦截")
                || normalized.contains("http 403")
                || normalized.contains("access denied");
    }

    private String sourceHost(String source) {
        try {
            return HttpUrl.get(source).host();
        } catch (Throwable ignored) {
            return "invalid-source";
        }
    }

    private List<SearchRequest> discoverSearchRequests(String source, String keyword, int page,
                                                        String profile) throws IOException {
        Document landing = fetchDocument(source, getSearchRequest("/"), DISCOVERY_CLIENT);
        return buildSearchRequestsFromForms(landing, source, keyword, page, profile);
    }

    static SearchRequest buildSearchRequestFromForm(Document document, String source,
                                                    String keyword, int page, String profile) {
        List<SearchRequest> requests =
                buildSearchRequestsFromForms(document, source, keyword, page, profile);
        return requests.isEmpty() ? null : requests.get(0);
    }

    static List<SearchRequest> buildSearchRequestsFromForms(Document document, String source,
                                                            String keyword, int page,
                                                            String profile) {
        List<SearchRequest> requests = new ArrayList<>();
        if (document == null) return requests;
        for (Element form : document.select("form")) {
            SearchRequest request = buildSearchRequestFromForm(
                    form, source, keyword, page, profile);
            if (request != null) requests.add(request);
        }
        return requests;
    }

    private static SearchRequest buildSearchRequestFromForm(Element form, String source,
                                                            String keyword, int page,
                                                            String profile) {
        if (form == null) return null;
        String searchField = "";
        for (Element field : form.select("input[name], textarea[name]")) {
            String name = field.attr("name").trim();
            if (isSearchFieldName(name)) {
                searchField = name;
                break;
            }
        }
        if (searchField.isEmpty()) return null;

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Element field : form.select("input[name], textarea[name], select[name]")) {
            if (field.hasAttr("disabled")) continue;
            String name = field.attr("name").trim();
            if (name.isEmpty()) continue;
            String type = field.attr("type").toLowerCase(Locale.ROOT);
            if ("submit".equals(type) || "button".equals(type) || "image".equals(type)
                    || "reset".equals(type) || "file".equals(type)) continue;
            if (("checkbox".equals(type) || "radio".equals(type))
                    && !field.hasAttr("checked")) continue;
            String value = field.tagName().equals("textarea")
                    ? field.text().trim() : field.attr("value").trim();
            if (field.tagName().equals("select")) {
                Element option = field.selectFirst("option[selected]");
                if (option == null) option = field.selectFirst("option");
                value = option == null ? "" : option.hasAttr("value")
                        ? option.attr("value") : option.text();
            }
            if (name.equals(searchField)) value = keyword == null ? "" : keyword;
            if (!value.isEmpty() || name.equals(searchField)) values.put(name, value);
        }
        if (values.isEmpty()) return null;

        String action = form.attr("action").trim();
        String target = action.isEmpty() ? source : resolveUrl(source, action);
        String method = form.attr("method").trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = "GET";
        if (page > 1 && !values.containsKey("pg")
                && !values.containsKey("page") && !values.containsKey("p")) {
            values.put("fed".equals(profile) ? "page" : "pg", String.valueOf(page));
        } else {
            for (String pageField : new String[]{"pg", "page", "p"}) {
                if (values.containsKey(pageField)) {
                    values.put(pageField, String.valueOf(page));
                    break;
                }
            }
        }
        if ("GET".equals(method)) {
            HttpUrl.Builder url = HttpUrl.get(target).newBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                url.removeAllQueryParameters(entry.getKey());
                url.addQueryParameter(entry.getKey(), entry.getValue());
            }
            return getSearchRequest(url.build().toString());
        }
        return new SearchRequest(method, target, values);
    }

    private static boolean isSearchFieldName(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return "wd".equals(normalized) || "searchword".equals(normalized)
                || "keyword".equals(normalized) || "kw".equals(normalized)
                || "q".equals(normalized);
    }

    private static String resolveUrl(String base, String path) {
        String action = path == null ? "" : path.trim();
        if (action.startsWith("http://") || action.startsWith("https://")) return action;
        HttpUrl baseUrl = HttpUrl.get(base);
        if (action.startsWith("//")) {
            String authority = action.substring(2).split("[/?#]", 2)[0];
            // Some templates mistakenly use //search as a root-relative search path.
            if (!authority.contains(".") && !authority.contains(":")) {
                action = "/" + action.substring(2);
            }
        }
        HttpUrl resolved = baseUrl.resolve(action);
        return resolved == null ? base : resolved.toString();
    }

    /**
     * 按站点模板构造搜索入口。该方法保持包可见，单测锁住不同 CMS 的真实请求协议。
     */
    static List<SearchRequest> buildSearchRequests(String profile, String keyword, int page) {
        int currentPage = Math.max(1, page);
        String encoded = encodeValue(keyword);
        List<SearchRequest> requests = new ArrayList<>();
        if ("fed".equals(profile)) {
            requests.add(getSearchRequest(
                    buildPath("/search.php", "searchword", keyword,
                            "page", String.valueOf(currentPage))));
            requests.add(getSearchRequest(
                    buildPath("/search.php", "wd", keyword,
                            "page", String.valueOf(currentPage))));
            requests.add(getSearchRequest(
                    "/index.php/vod/search/page/" + currentPage + "/wd/" + encoded + ".html"));
        } else if ("zanpian".equals(profile)) {
            StringBuilder path = new StringBuilder("/index.php?s=vod-search-wd-")
                    .append(encoded);
            if (currentPage > 1) path.append("-p-").append(currentPage);
            path.append("-ajax");
            requests.add(new SearchRequest("GET", path.toString(),
                    Collections.<String, String>emptyMap(), true));
        } else {
            requests.add(getSearchRequest(buildPath(
                    "/vodsearch/-------------.html", "wd", keyword,
                    "pg", String.valueOf(currentPage))));
            requests.add(getSearchRequest(buildPath(
                    "/vod/search.html", "wd", keyword,
                    "page", String.valueOf(currentPage))));
            requests.add(getSearchRequest(
                    "/index.php/vod/search/page/" + currentPage + "/wd/" + encoded + ".html"));
        }
        return requests;
    }

    private static SearchRequest getSearchRequest(String path) {
        return new SearchRequest("GET", path, Collections.<String, String>emptyMap());
    }

    private String detail(String source, String profile, String ids) throws IOException {
        String id = ids.split(",", 2)[0].trim();
        String detailUrl = resolve(source, id);
        Document document = fetchDocument(detailUrl);
        JsonObject vod = new JsonObject();
        String content = firstText(document, "meta[name=description]", ".fed-deta-content",
                ".yplx_c3", ".detail-content", ".vod_content");
        String name = firstText(document, "meta[property=og:title]", "meta[name=twitter:title]",
                ".fed-deta-info h1", ".con_name h1", ".detail-info h1",
                ".module-info-heading h1", ".video-title", ".vod-name", "h1");
        name = resolveDetailTitle(name, document.title(), content);
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", name);
        vod.addProperty("vod_pic", firstImage(document));
        vod.addProperty("vod_year", firstYear(document.text()));
        vod.addProperty("vod_content", content);
        vod.addProperty("vod_remarks", "网页线路");

        LinkedHashMap<String, LinkedHashMap<String, String>> playbackLines =
                extractPlayableEpisodeLines(document, detailUrl, profile);
        if (playbackLines.isEmpty()) {
            String direct = firstMediaUrl(document.html());
            if (!direct.isEmpty()) {
                LinkedHashMap<String, String> episodes = new LinkedHashMap<>();
                episodes.put(direct, "正片$" + direct);
                playbackLines.put("1", episodes);
            }
        }
        StringBuilder playFrom = new StringBuilder();
        StringBuilder playUrls = new StringBuilder();
        boolean multipleLines = playbackLines.size() > 1;
        for (Map.Entry<String, LinkedHashMap<String, String>> line : playbackLines.entrySet()) {
            if (playFrom.length() > 0) {
                playFrom.append("$$$");
                playUrls.append("$$$");
            }
            playFrom.append(multipleLines ? "网页线路" + line.getKey() : "网页线路");
            boolean firstEpisode = true;
            for (String episode : line.getValue().values()) {
                if (!firstEpisode) playUrls.append('#');
                playUrls.append(episode);
                firstEpisode = false;
            }
        }
        vod.addProperty("vod_play_from", playFrom.toString());
        vod.addProperty("vod_play_url", playUrls.toString());

        JsonArray list = new JsonArray();
        list.add(vod);
        return pageJson(list, 1);
    }

    LinkedHashSet<String> extractPlayableEpisodes(Document document, String detailUrl, String profile) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (LinkedHashMap<String, String> line
                : extractPlayableEpisodeLines(document, detailUrl, profile).values()) {
            links.addAll(line.values());
        }
        return links;
    }

    LinkedHashMap<String, LinkedHashMap<String, String>> extractPlayableEpisodeLines(
            Document document, String detailUrl, String profile) {
        LinkedHashMap<String, LinkedHashMap<String, String>> episodesByLine =
                new LinkedHashMap<>();
        if (document == null) return episodesByLine;
        String detailContentId = detailContentId(detailUrl);
        Elements playable = document.select("a[href]");
        for (Element link : playable) {
            String href = link.attr("href").trim();
            if (!isPlayablePage(href, profile)) continue;
            String absolute = resolve(detailUrl, href);
            if (!isSameDetailContent(absolute, detailContentId)) continue;
            String playUrl = withoutFragment(absolute);
            if (!playUrl.equals(detailUrl)) {
                String lineId = playbackLineId(playUrl);
                LinkedHashMap<String, String> episodesByUrl = episodesByLine.get(lineId);
                if (episodesByUrl == null) {
                    if (episodesByLine.size() >= MAX_PLAYBACK_LINES) continue;
                    episodesByUrl = new LinkedHashMap<>();
                    episodesByLine.put(lineId, episodesByUrl);
                }
                if (!episodesByUrl.containsKey(playUrl)
                        && episodesByUrl.size() >= MAX_EPISODES) continue;
                String item = episode(link, absolute, playUrl);
                String previous = episodesByUrl.get(playUrl);
                if (previous == null || (isGenericEpisodeEntry(previous)
                        && !isGenericEpisodeEntry(item))) {
                    episodesByUrl.put(playUrl, item);
                }
            }
        }
        episodesByLine.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return episodesByLine;
    }

    private String detailContentId(String detailUrl) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("/(\\d+)\\.html/?$")
                    .matcher(HttpUrl.get(detailUrl).encodedPath());
            return matcher.find() ? matcher.group(1) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isSameDetailContent(String playUrl, String detailContentId) {
        if (detailContentId.isEmpty()) return true;
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "/(?:playmov|play|vodplay)/(\\d+)-\\d+-\\d+(?:\\.html)?/?$")
                    .matcher(HttpUrl.get(playUrl).encodedPath());
            return !matcher.find() || detailContentId.equals(matcher.group(1));
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String playbackLineId(String url) {
        try {
            String path = HttpUrl.get(url).encodedPath();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "/(?:playmov|play|vodplay)/(\\d+)-(\\d+)-\\d+(?:\\.html)?/?$")
                    .matcher(path);
            return matcher.find() ? matcher.group(2) : "1";
        } catch (Throwable ignored) {
            return "1";
        }
    }

    static String resolveDetailTitle(String candidate, String pageTitle, String content) {
        String quotedTitle = inferQuotedTitleFromContent(content);
        if (!quotedTitle.isEmpty()) return quotedTitle;
        String normalizedContent = normalizeSearchText(content);
        String title = cleanTitle(candidate);
        if (!title.isEmpty() && normalizedContent.contains(normalizeSearchText(title))) {
            return title;
        }
        if (!title.isEmpty()) {
            for (String separator : new String[]{" - ", " | ", "_", "-", "—", "｜"}) {
                int index = title.indexOf(separator);
                if (index <= 1) continue;
                String prefix = cleanTitle(title.substring(0, index));
                if (normalizedContent.contains(normalizeSearchText(prefix))) return prefix;
            }
        }
        String inferred = inferTitleFromContent(content);
        if (!inferred.isEmpty()) return inferred;
        return title.isEmpty() ? cleanTitle(pageTitle) : title;
    }

    private static String inferTitleFromContent(String content) {
        if (content == null || content.trim().isEmpty()) return "";
        String cleaned = cleanTitle(content);
        String quotedTitle = inferQuotedTitleFromContent(cleaned);
        if (!quotedTitle.isEmpty()) return quotedTitle;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^(.{2,40}?)(?:于(?:19|20)\\d{2}播出|免费在线观看|在线播放|"
                        + "剧情简介\\s*[:：]?|剧情介绍\\s*[:：]?)")
                .matcher(cleaned);
        if (!matcher.find()) return "";
        return matcher.group(1).replaceAll("^[《【\\s]+|[》】\\s]+$", "").trim();
    }

    private static String inferQuotedTitleFromContent(String content) {
        if (content == null || content.trim().isEmpty()) return "";
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile(
                "[《【「『]([^》】」』]{2,40})[》】」』]")
                .matcher(cleanTitle(content));
        return quoted.find() ? cleanTitle(quoted.group(1)) : "";
    }

    JsonArray parseCards(Document document, String profile, String keyword) {
        JsonArray result = new JsonArray();
        if (document == null) return result;
        Set<String> seen = new HashSet<>();
        if (isBlockedPage(document)) return result;
        if (!"zanpian".equals(profile) && !hasSearchEvidence(document, keyword)) return result;
        Elements candidates = searchCandidates(document, profile, keyword);
        for (Element link : candidates) {
            String href = link.attr("href").trim();
            String id = detailId(document, href, profile);
            if (id.isEmpty()) continue;
            Element card = findCard(link);
            String name = cardName(link, card);
            if (name.isEmpty()) continue;
            String context = card == null ? textOf(link) : textOf(card);
            if (!containsSearchKeyword(name, context, keyword)) continue;
            if (!seen.add(id)) continue;
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", id);
            vod.addProperty("vod_name", cleanTitle(name));
            vod.addProperty("vod_pic", imageOf(link, card));
            vod.addProperty("vod_remarks", remarkOf(link, card));
            vod.addProperty("vod_year", firstYear(name));
            vod.addProperty("vod_play_from", "");
            vod.addProperty("vod_play_url", "");
            result.add(vod);
            if (result.size() >= MAX_RESULTS) break;
        }
        return result;
    }

    private Elements searchCandidates(Document document, String profile, String keyword) {
        Elements candidates;
        if ("fed".equals(profile)) {
            candidates = document.select(
                    "a.fed-list-title[href], .fed-search-list a[href], "
                            + ".fed-list-info li a[href], .fed-list-item a[href]");
        } else if ("zanpian".equals(profile)) {
            candidates = document.select(
                    "li a.module_play_img[href], li a.play-img[href]");
        } else {
            candidates = document.select(
                    "#searchList li a[href], #searchList a[href], "
                            + ".search-list li a[href], .search-list a[href], "
                            + ".module-search-item a[href], .module-items .module-search-item a[href], "
                            + ".hl-list-item a[href], .sr_lists li a[href], "
                            + "ul.stui-vodlist__media li a[href]");
            // 仅在带明确搜索语义的结果列表内兼容旧模板，不退回全站影片列表。
            if (candidates.isEmpty() && hasSearchEvidence(document, keyword)) {
                candidates = document.select(
                        "#searchList li a[href], .search-list li a[href], "
                                + ".search-result li a[href], .stui-vodlist__media li a[href], "
                                + ".module-items .module-item a[href], "
                                + ".search_list a[href], .result-list a[href], "
                                + ".vod-list a[href], .stui-vodlist a[href]");
            }
        }
        if (candidates.isEmpty() && !"zanpian".equals(profile)
                && hasSearchEvidence(document, keyword)) {
            candidates = document.select("a[href]");
        }
        return candidates;
    }

    private boolean hasSearchEvidence(Document document, String keyword) {
        String evidence = document.title() + " "
                + document.select("h1, h2, h3, .search-title, .search-keyword, "
                + ".fed-part-case, .search-result-title").text();
        String lower = evidence.toLowerCase(Locale.ROOT);
        if (lower.contains("search") || evidence.contains("搜索") || evidence.contains("结果")) {
            return true;
        }
        try {
            HttpUrl page = HttpUrl.get(document.location());
            String pathAndQuery = (page.encodedPath() + " " + page.encodedQuery())
                    .toLowerCase(Locale.ROOT);
            if (pathAndQuery.contains("search") || pathAndQuery.contains("vodsearch")) return true;
            for (String field : new String[]{"wd", "searchword", "keyword", "q"}) {
                String submitted = page.queryParameter(field);
                if (!submitted.isEmpty()
                        && normalizeSearchText(submitted).equals(normalizeSearchText(keyword))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Parsed fixtures and unusual URLs may not carry a location.
        }
        for (Element input : document.select("input[name=wd], input[name=searchword], "
                + "input[name=keyword], input[name=q]")) {
            String submitted = input.attr("value");
            if (!submitted.isEmpty()
                    && normalizeSearchText(submitted).equals(normalizeSearchText(keyword))) {
                return true;
            }
        }
        return false;
    }

    static boolean isBlockedPage(Document document) {
        if (document == null) return true;
        String text = (document.title() + " " + document.text()).toLowerCase(Locale.ROOT);
        String[] markers = {
                "系统安全验证", "请输入验证码", "访问验证", "请完成验证",
                "身份验证", "安全验证", "滑动验证",
                "captcha", "cloudflare", "just a moment", "attention required",
                "403 forbidden", "access denied", "checking your browser"
        };
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    static boolean isSearchDisabledPage(Document document) {
        if (document == null) return false;
        String text = (document.title() + " " + document.text()).toLowerCase(Locale.ROOT);
        return text.contains("搜索功能关闭中")
                || text.contains("搜索功能已关闭")
                || text.contains("搜索暂未开放");
    }

    static boolean containsSearchKeyword(String title, String context, String keyword) {
        String normalizedKeyword = normalizeSearchText(keyword);
        if (normalizedKeyword.length() < 2) return false;
        String normalizedResult = normalizeSearchText(
                (title == null ? "" : title) + " " + (context == null ? "" : context));
        return normalizedResult.contains(normalizedKeyword);
    }

    private static String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}\\p{P}\\p{S}]+", "");
    }

    private boolean isDetailPage(String href, String profile) {
        String lower = href.toLowerCase(Locale.ROOT);
        if ("zanpian".equals(profile) && lower.matches(
                "^/[a-z0-9_-]+/[a-z0-9_-]+/$")) {
            return true;
        }
        if (!lower.startsWith("/") || lower.startsWith("//")) return false;
        if (lower.contains("/v/") || lower.contains("/content")
                || lower.contains("/detail") || lower.contains("/movie")
                || lower.contains("/tvseries") || lower.contains("/animation")
                || lower.contains("/arts") || lower.contains("/voddetail")
                || lower.contains("/vod/")) {
            return true;
        }
        return lower.endsWith(".html") && !lower.contains("/search")
                && !lower.contains("/play") && !lower.contains("/type")
                && !lower.contains("/topic") && !lower.contains("/actor")
                && !lower.contains("/tag");
    }

    private String detailId(Document document, String href, String profile) {
        if (href == null || href.trim().isEmpty()) return "";
        String trimmed = href.trim();
        try {
            HttpUrl page = HttpUrl.get(document.location());
            HttpUrl resolved = page.resolve(trimmed);
            if (resolved == null || !page.host().equalsIgnoreCase(resolved.host())) return "";
            String path = resolved.encodedPath();
            boolean shortDetail = "/s".equalsIgnoreCase(path) && hasDetailQuery(resolved);
            if ((!isDetailPage(path, profile) && !shortDetail)
                    || isPlayablePage(path, profile)) return "";
            return path + (resolved.encodedQuery() == null
                    ? "" : "?" + resolved.encodedQuery());
        } catch (Throwable ignored) {
            if (trimmed.startsWith("/") && !trimmed.startsWith("//")
                    && isDetailPage(trimmed, profile) && !isPlayablePage(trimmed, profile)) {
                return trimmed;
            }
            return "";
        }
    }

    private static boolean hasDetailQuery(HttpUrl url) {
        for (String name : url.queryParameterNames()) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.equals("wd") || normalized.equals("keyword")
                    || normalized.equals("searchword") || normalized.equals("q")
                    || normalized.equals("page") || normalized.equals("pg")
                    || normalized.equals("p") || normalized.equals("type")) {
                continue;
            }
            if (!url.queryParameterValues(name).isEmpty()) return true;
        }
        return false;
    }

    static boolean isPlayablePage(String href, String profile) {
        String lower = href.toLowerCase(Locale.ROOT);
        if ("zanpian".equals(profile) && lower.matches(
                "^/[a-z0-9_-]+/[a-z0-9_-]+/\\d+-\\d+\\.html(?:\\?.*)?$")) {
            return true;
        }
        return lower.contains("/p/") || lower.contains("/play")
                || lower.contains("/vodplay") || lower.contains("/player")
                || lower.endsWith(".m3u8") || lower.endsWith(".mp4");
    }

    private String episode(Element link, String absolute, String playUrl) {
        String title = attrOrText(link, "title");
        if (title.isEmpty()) title = textOf(link);
        String fragment = "";
        try {
            String parsedFragment = HttpUrl.get(absolute).fragment();
            if (parsedFragment != null) fragment = parsedFragment;
        } catch (Throwable ignored) {
            // Keep the anchor text if the URL is not parseable.
        }
        if (!fragment.isEmpty() && isGenericPlayTitle(title)
                && fragment.matches("第\\s*\\d+\\s*[集话期]")) {
            title = fragment;
        }
        if (title.isEmpty() || isGenericPlayTitle(title)) title = "正片";
        return cleanEpisode(title) + "$" + playUrl;
    }

    private boolean isGenericPlayTitle(String title) {
        String normalized = normalizeSearchText(title);
        return normalized.isEmpty() || normalized.equals("正片")
                || normalized.contains("免费观看")
                || normalized.contains("在线播放") || normalized.contains("立即播放")
                || normalized.contains("播放正片");
    }

    private boolean isGenericEpisodeEntry(String item) {
        int delimiter = item.indexOf('$');
        String title = delimiter < 0 ? item : item.substring(0, delimiter);
        return isGenericPlayTitle(title);
    }

    private String withoutFragment(String url) {
        try {
            return HttpUrl.get(url).newBuilder().fragment(null).build().toString();
        } catch (Throwable ignored) {
            int fragment = url.indexOf('#');
            return fragment < 0 ? url : url.substring(0, fragment);
        }
    }

    private Document fetchDocument(String source, String path) throws IOException {
        return fetchDocument(source, getSearchRequest(path));
    }

    private Document fetchDocument(String source, SearchRequest searchRequest) throws IOException {
        return fetchDocument(source, searchRequest, SEARCH_CLIENT);
    }

    private Document fetchDocument(String source, SearchRequest searchRequest, OkHttpClient client)
            throws IOException {
        String url = resolve(source, searchRequest.path);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", source)
                .header("Origin", origin(source));
        if (searchRequest.htmlInJson) {
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01");
        }
        if ("POST".equalsIgnoreCase(searchRequest.method)) {
            FormBody.Builder body = new FormBody.Builder();
            for (Map.Entry<String, String> entry : searchRequest.form.entrySet()) {
                body.add(entry.getKey(), entry.getValue());
            }
            requestBuilder.post(body.build());
        } else {
            requestBuilder.get();
        }
        Request request = requestBuilder.build();
        if (!searchRequest.htmlInJson) return executeDocument(url, request, client);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            Document document = parseHtmlInJson(
                    response.body().string(), response.request().url().toString());
            if (isBlockedPage(document)) {
                throw new IOException("站点安全验证或拦截");
            }
            return document;
        }
    }

    static Document parseHtmlInJson(String response, String baseUri) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonObject()) {
                throw new IOException("搜索响应缺少 data");
            }
            JsonElement html = data.getAsJsonObject().get("ajaxtxt");
            if (html == null || !html.isJsonPrimitive()) {
                throw new IOException("搜索响应缺少 ajaxtxt");
            }
            return Jsoup.parse(html.getAsString(), baseUri);
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("搜索 JSON 响应格式无效", error);
        }
    }

    private Document fetchDocument(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", url)
                .build();
        return executeDocument(url, request);
    }

    private Document executeDocument(String url, Request request) throws IOException {
        return executeDocument(url, request, CLIENT);
    }

    private Document executeDocument(String url, Request request, OkHttpClient client) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            Document document = Jsoup.parse(
                    response.body().string(), response.request().url().toString());
            if (isBlockedPage(document)) {
                throw new IOException("站点安全验证或拦截");
            }
            return document;
        }
    }

    private String origin(String source) {
        try {
            HttpUrl url = HttpUrl.get(source);
            return url.scheme() + "://" + url.host();
        } catch (Throwable ignored) {
            return source;
        }
    }

    private String resolve(String base, String path) {
        if (path == null || path.isEmpty()) return base;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return HttpUrl.get(base).resolve(path).toString();
    }

    private static String buildPath(String path, String key, String value, String pageKey, String page) {
        return path + "?" + key + "=" + encodeValue(value) + "&" + pageKey + "=" + encodeValue(page);
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
        return error(message, 1);
    }

    private String error(String message, int page) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("msg", message);
        root.addProperty("page", page);
        root.addProperty("pagecount", page);
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

    private String imageOf(Element link, Element card) {
        String image = imageFrom(link);
        return !image.isEmpty() || card == null ? image : imageFrom(card);
    }

    private String imageFrom(Element container) {
        if (container == null) return "";
        String image = imageAttr(container);
        if (!image.isEmpty()) return image;
        for (Element candidate : container.select(
                "img, [data-original], [data-src], [data-lazy]")) {
            image = imageAttr(candidate);
            if (!image.isEmpty()) return image;
        }
        return "";
    }

    private String imageAttr(Element image) {
        for (String key : new String[]{"data-original", "data-src", "data-lazy", "src"}) {
            String value = image.attr(key).trim();
            if (!value.isEmpty() && !value.contains("blank")) return value;
        }
        return "";
    }

    private String remarkOf(Element link, Element card) {
        if (card != null) {
            Element remark = card.selectFirst(".pic-text, .text-muted, .remarks, "
                    + ".module-item-note, .fed-list-remarks, .video-serial");
            if (remark != null && !remark.text().trim().isEmpty()) return remark.text().trim();
        }
        Element parent = link.parent();
        return parent == null ? "" : parent.text().trim();
    }

    private Element findCard(Element link) {
        Element current = link == null ? null : link.parent();
        for (int i = 0; current != null && i < 5; i++, current = current.parent()) {
            String tag = current.tagName();
            Set<String> classes = current.classNames();
            if ("li".equals(tag) || "article".equals(tag)
                    || classes.contains("item") || classes.contains("module-item")
                    || classes.contains("module-search-item")
                    || classes.contains("stui-vodlist__item")
                    || classes.contains("fed-list-item")
                    || classes.contains("card") || classes.contains("vod")) {
                return current;
            }
        }
        return link;
    }

    private String cardName(Element link, Element card) {
        String name = attrOrText(link, "title");
        if (!name.isEmpty()) return name;
        Element image = link.selectFirst("img[alt]");
        if (image != null && !image.attr("alt").trim().isEmpty()) {
            return image.attr("alt").trim();
        }
        if (card == null) return textOf(link);
        Element title = card.selectFirst(
                "h1 a[title], h2 a[title], h3 a[title], h4 a[title], h5 a[title], "
                        + ".play-txt h5 a, h1, h2, h3, h4, h5, .title, .name, .module-card-item-title, "
                        + ".fed-list-title, [title]");
        if (title != null) {
            name = attrOrText(title, "title");
            if (name.isEmpty()) name = textOf(title);
            if (!name.isEmpty()) return name;
        }
        return textOf(link);
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

    private static String cleanTitle(String value) {
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

    private static String encodeValue(String value) {
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
