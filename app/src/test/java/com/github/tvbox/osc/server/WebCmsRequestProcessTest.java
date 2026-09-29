package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;

public class WebCmsRequestProcessTest {
    @Test
    public void onlySearchAndDetailRequestsReachWebSite() {
        Map<String, String> search = new HashMap<>();
        search.put("wd", "沉默的荣耀");
        assertTrue(WebCmsRequestProcess.isSearchOrDetailRequest(search));

        Map<String, String> detail = new HashMap<>();
        detail.put("ids", "/v/27227.html");
        assertTrue(WebCmsRequestProcess.isSearchOrDetailRequest(detail));

        Map<String, String> home = new HashMap<>();
        home.put("t", "1");
        assertFalse(WebCmsRequestProcess.isSearchOrDetailRequest(home));
        assertFalse(WebCmsRequestProcess.isSearchOrDetailRequest(new HashMap<>()));
    }

    @Test
    public void zanpianSearchUsesAjaxJsonEndpoint() {
        List<WebCmsRequestProcess.SearchRequest> requests =
                WebCmsRequestProcess.buildSearchRequests("zanpian", "沉默的荣耀", 1);

        assertFalse(requests.isEmpty());
        WebCmsRequestProcess.SearchRequest request = requests.get(0);
        assertEquals("GET", request.method);
        assertEquals("/index.php?s=vod-search-wd-%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80-ajax",
                request.path);
        assertTrue(request.htmlInJson);
    }

    @Test
    public void discoversMacCmsSearchFormAndUsesItsActionAndField() {
        WebCmsRequestProcess.SearchRequest request =
                WebCmsRequestProcess.buildSearchRequestFromForm(
                        Jsoup.parse("<form method='get' action='/vodsearch/-------------.html'>"
                                + "<input name='wd'><input type='submit'></form>",
                                "https://source.example/"),
                        "https://source.example/", "沉默的荣耀", 1, "maccms");

        assertEquals("GET", request.method);
        assertEquals("/vodsearch/-------------.html",
                HttpUrl.get(request.path).encodedPath());
        assertEquals("沉默的荣耀",
                HttpUrl.get(request.path).queryParameter("wd"));
    }

    @Test
    public void treatsProtocolRelativeSearchActionAsPathWhenItHasNoDomain() {
        WebCmsRequestProcess.SearchRequest request =
                WebCmsRequestProcess.buildSearchRequestFromForm(
                        Jsoup.parse("<form method='get' action='//search'>"
                                + "<input name='wd'></form>",
                                "https://source.example/"),
                        "https://source.example/", "沉默的荣耀", 1, "maccms");

        assertEquals("https://source.example/search",
                HttpUrl.get(request.path).newBuilder().query(null).build().toString());
        assertEquals("沉默的荣耀",
                HttpUrl.get(request.path).queryParameter("wd"));
    }

    @Test
    public void discoversFedSearchFormAndPreservesHiddenFieldsForPost() {
        WebCmsRequestProcess.SearchRequest request =
                WebCmsRequestProcess.buildSearchRequestFromForm(
                        Jsoup.parse("<form method='post' action='/search.php'>"
                                + "<input type='hidden' name='token' value='abc'>"
                                + "<input name='searchword'></form>",
                                "https://source.example/"),
                        "https://source.example/", "沉默的荣耀", 2, "fed");

        assertEquals("POST", request.method);
        assertEquals("https://source.example/search.php", request.path);
        assertEquals("abc", request.form.get("token"));
        assertEquals("沉默的荣耀", request.form.get("searchword"));
        assertEquals("2", request.form.get("page"));
    }

    @Test
    public void extractsMacCmsPosterFromLazyloadContainer() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<div id='searchList'><div class='module-search-item'>"
                        + "<a href='/voddetail/772.html' title='沉默的荣耀'>"
                        + "<div class='module-item-pic'><div class='lazyload' "
                        + "data-original='https://img.example/chenmo.jpg'></div></div>"
                        + "</a></div></div></body></html>",
                "https://source.example/vod/search/?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("https://img.example/chenmo.jpg",
                result.get(0).getAsJsonObject().get("vod_pic").getAsString());
    }

    @Test
    public void discoversAllSearchFormsAndKeepsGetAndPostFallbacks() {
        List<WebCmsRequestProcess.SearchRequest> requests =
                WebCmsRequestProcess.buildSearchRequestsFromForms(
                        Jsoup.parse("<form method='get' action='/search/-------------/'>"
                                        + "<input name='wd'></form>"
                                        + "<form method='post' action='/search/-------------/'>"
                                        + "<input type='hidden' name='token' value='abc'>"
                                        + "<input name='wd'><button name='submit'>搜索</button></form>",
                                "http://www.lanyajc.com/"),
                        "http://www.lanyajc.com/", "沉默的荣耀", 1, "maccms");

        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).method);
        assertEquals("http://www.lanyajc.com/search/-------------/",
                HttpUrl.get(requests.get(0).path).newBuilder().query(null).build().toString());
        assertEquals("POST", requests.get(1).method);
        assertEquals("abc", requests.get(1).form.get("token"));
        assertEquals("沉默的荣耀", requests.get(1).form.get("wd"));
        assertFalse(requests.get(1).form.containsKey("submit"));
    }

    @Test
    public void discoversArrayNamedWdFieldFromZanpianGetForm() {
        WebCmsRequestProcess.SearchRequest request =
                WebCmsRequestProcess.buildSearchRequestFromForm(
                        Jsoup.parse("<form class='zanpian-search' name='formsearch' "
                                        + "action='//search/-------------.html' method='get'>"
                                        + "<input class='zanpian-wd' type='search' name='wd[]'>"
                                        + "<button type='submit'>搜索</button></form>",
                                "http://www.knshuichuli.com/"),
                        "http://www.knshuichuli.com/", "沉默的荣耀", 1, "maccms");

        assertEquals("沉默的荣耀",
                HttpUrl.get(request.path).queryParameter("wd[]"));
        assertFalse(HttpUrl.get(request.path).queryParameterNames().contains("wd"));
    }

    @Test
    public void discoversArrayNamedWdFieldFromPostForm() {
        List<WebCmsRequestProcess.SearchRequest> requests =
                WebCmsRequestProcess.buildSearchRequestsFromForms(
                        Jsoup.parse("<form method='post' action='/search/-------------.html'>"
                                        + "<input type='hidden' name='token' value='abc'>"
                                        + "<input type='search' name='wd[]'></form>",
                                "https://source.example/"),
                        "https://source.example/", "庆余年", 1, "maccms");

        assertEquals(1, requests.size());
        assertEquals("庆余年", requests.get(0).form.get("wd[]"));
        assertFalse(requests.get(0).form.containsKey("wd"));
        assertEquals("abc", requests.get(0).form.get("token"));
    }

    @Test
    public void discoversHongguoSearchwordForm() {
        WebCmsRequestProcess.SearchRequest request =
                WebCmsRequestProcess.buildSearchRequestFromForm(
                        Jsoup.parse("<form method='get' action='/search.php'>"
                                + "<input name='searchword'></form>",
                                "https://m.syshuoyuan.com/"),
                        "https://m.syshuoyuan.com/", "沉默的荣耀", 1, "maccms");

        assertEquals("https://m.syshuoyuan.com/search.php?searchword="
                        + "%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80",
                request.path);
    }

    @Test
    public void discoversZanpianPostFormAsFallback() {
        List<WebCmsRequestProcess.SearchRequest> requests =
                WebCmsRequestProcess.buildSearchRequestsFromForms(
                        Jsoup.parse("<form method='post' action='/vod-search.html'>"
                                + "<input name='wd'></form>",
                                "https://www.zanpian.org/"),
                        "https://www.zanpian.org/", "沉默的荣耀", 1, "zanpian");

        assertEquals(1, requests.size());
        assertEquals("POST", requests.get(0).method);
        assertEquals("https://www.zanpian.org/vod-search.html",
                requests.get(0).path);
        assertEquals("沉默的荣耀", requests.get(0).form.get("wd"));
    }

    @Test
    public void parsesZanpianAjaxHtmlAndUsesResultTitleNotEpisodeRemark() throws Exception {
        String response = "{\"data\":{\"ajaxtxt\":\"<li>"
                + "<a class=\\\"module_play_img\\\" href=\\\"/China/chenmoderongyao/\\\">"
                + "<img src=\\\"https://img.example/poster.jpg\\\"/>"
                + "<label>共39集 连载至第39集</label></a>"
                + "<div class=\\\"play-txt\\\"><h5><a href=\\\"/China/chenmoderongyao/\\\">"
                + "<font>沉默的荣耀</font></a></h5></div></li>\"}}";

        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(
                WebCmsRequestProcess.parseHtmlInJson(response, "https://www.zanpian.org/"),
                "zanpian", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("沉默的荣耀",
                result.get(0).getAsJsonObject().get("vod_name").getAsString());
        assertEquals("/China/chenmoderongyao/",
                result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void zanpianEpisodeUrlsAreRecognizedAsPlaybackPages() {
        assertTrue(WebCmsRequestProcess.isPlayablePage(
                "/China/chenmoderongyao/0-39.html", "zanpian"));
        assertFalse(WebCmsRequestProcess.isPlayablePage(
                "/China/chenmoderongyao/", "zanpian"));
    }

    @Test
    public void extractsZanpianEpisodePagesAndSkipsRelatedTitles() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        java.util.Set<String> episodes = process.extractPlayableEpisodes(
                Jsoup.parse("<div>"
                        + "<a href='/China/chenmoderongyao/0-39.html'>第39集</a>"
                        + "<a href='/China/chenmoderongyao/0-38.html'>第38集</a>"
                        + "<a href='/China/wobushidashi/'>我不是大师</a>"
                        + "</div>", "https://www.zanpian.org/China/chenmoderongyao/"),
                "https://www.zanpian.org/China/chenmoderongyao/", "zanpian");

        assertEquals(2, episodes.size());
        assertTrue(episodes.contains(
                "第39集$https://www.zanpian.org/China/chenmoderongyao/0-39.html"));
        assertTrue(episodes.contains(
                "第38集$https://www.zanpian.org/China/chenmoderongyao/0-38.html"));
    }

    @Test
    public void macCmsPlaybackSourcesAreKeptInSeparateEpisodeLines() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        Map<String, java.util.LinkedHashMap<String, String>> lines =
                process.extractPlayableEpisodeLines(
                        Jsoup.parse("<div>"
                                + "<a href='/playmov/25397-1-1.html'>第01集</a>"
                                + "<a href='/playmov/25397-1-2.html'>第02集</a>"
                                + "<a href='/playmov/25397-4-1.html'>第01集</a>"
                                + "<a href='/playmov/25397-4-2.html'>第02集</a>"
                                + "</div>", "https://old.sliiic.org/sli/25397.html"),
                        "https://old.sliiic.org/sli/25397.html", "maccms");

        assertEquals(2, lines.size());
        assertEquals(2, lines.get("1").size());
        assertEquals(2, lines.get("4").size());
        assertTrue(lines.get("1").containsValue(
                "第01集$https://old.sliiic.org/playmov/25397-1-1.html"));
        assertTrue(lines.get("4").containsValue(
                "第01集$https://old.sliiic.org/playmov/25397-4-1.html"));
    }

    @Test
    public void detailPlaybackSkipsEpisodesFromOtherRecommendedTitles() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        String detailUrl = "https://source.example/vod/bkdlj/60143.html";
        Map<String, java.util.LinkedHashMap<String, String>> lines =
                process.extractPlayableEpisodeLines(
                        Jsoup.parse("<div>"
                                + "<a href='/vod/bkdlj/play/60143-1-1.html'>第01集</a>"
                                + "<a href='/vod/lzdlj/play/60392-1-1.html'>推荐影片第01集</a>"
                                + "</div>", detailUrl),
                        detailUrl, "maccms");

        assertEquals(1, lines.size());
        assertEquals(1, lines.get("1").size());
        assertTrue(lines.get("1").containsValue(
                "第01集$https://source.example/vod/bkdlj/play/60143-1-1.html"));
    }

    @Test
    public void stripsEpisodeFragmentsBeforeSerializingPlayListSeparators() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        String detailUrl = "https://old.sliiic.org/sli/25397.html";
        java.util.Set<String> episodes = process.extractPlayableEpisodes(
                Jsoup.parse("<div>"
                        + "<a href='/playmov/25397-1-1.html'>免费观看</a>"
                        + "<a href='/playmov/25397-1-1.html#第01集'>第01集</a>"
                        + "<a href='/playmov/25397-1-2.html#第02集' title='第02集'>"
                        + "在线播放</a></div>", detailUrl),
                detailUrl, "maccms");

        assertEquals(2, episodes.size());
        assertTrue(episodes.contains(
                "第01集$https://old.sliiic.org/playmov/25397-1-1.html"));
        assertTrue(episodes.contains(
                "第02集$https://old.sliiic.org/playmov/25397-1-2.html"));
        for (String episode : episodes) assertFalse(episode.contains("#"));
    }

    @Test
    public void detailTitlePrefersMovieNameInDescriptionOverSiteHeading() {
        assertEquals("沉默的荣耀", WebCmsRequestProcess.resolveDetailTitle(
                "策驰影视", "策驰影视", "沉默的荣耀于2025播出,杨亚洲执导，于和伟主演"));
        assertEquals("沉默的荣耀", WebCmsRequestProcess.resolveDetailTitle(
                "策驰影视", "策驰影视", "沉默的荣耀免费在线观看剧情简介:1949年8月"));
        assertEquals("庆余年 第一季", WebCmsRequestProcess.resolveDetailTitle(
                "资源站点为您提供最新《庆余年 第一季",
                "《庆余年 第一季》连续剧热播-资源站点",
                "资源站点为您提供最新《庆余年 第一季》免费在线观看"));
    }

    @Test
    public void identifiesServerSideDisabledSearchPage() {
        assertTrue(WebCmsRequestProcess.isSearchDisabledPage(Jsoup.parse(
                "<html><head><title>系统提示...</title></head>"
                        + "<body><div>搜索功能关闭中</div></body></html>")));
        assertFalse(WebCmsRequestProcess.isSearchDisabledPage(Jsoup.parse(
                "<html><head><title>沉默的荣耀搜索结果</title></head>"
                        + "<body><div>暂无相关视频</div></body></html>")));
    }

    @Test
    public void identifiesIdentityVerificationPagesAsBlocked() {
        assertTrue(WebCmsRequestProcess.isBlockedPage(Jsoup.parse(
                "<html><head><title>身份验证</title></head>"
                        + "<body><div>安全验证，防止自动请求</div></body></html>")));
        assertTrue(WebCmsRequestProcess.isBlockedPage(Jsoup.parse(
                "<html><head><title>滑动验证</title></head><body>请完成验证</body></html>")));
    }

    @Test
    public void reportsSecurityBlocksButNotOrdinaryNetworkTimeouts() {
        assertTrue(WebCmsRequestProcess.isBlockedSearchFailure("站点安全验证或拦截"));
        assertTrue(WebCmsRequestProcess.isBlockedSearchFailure("HTTP 403"));
        assertTrue(WebCmsRequestProcess.isBlockedSearchFailure("access denied"));
        assertFalse(WebCmsRequestProcess.isBlockedSearchFailure("timeout"));
    }

    @Test
    public void keywordMatchDoesNotLeakAcrossTheWholeSearchList() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<div id='searchList'><div class='module-items'>"
                        + "<a href='/v/right.html' title='沉默的荣耀'>沉默的荣耀</a>"
                        + "<a href='/v/wrong.html' title='固定推荐'>固定推荐</a>"
                        + "</div></div></body></html>"), "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("/v/right.html",
                result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void matchingDuplicateDetailLinkIsNotDiscardedByEarlierNonMatchingLink() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<div id='searchList'>"
                        + "<div class='item'><a href='/v/772.html' title='固定推荐'>封面</a></div>"
                        + "<div class='item'><a href='/v/772.html' title='沉默的荣耀'>沉默的荣耀</a></div>"
                        + "</div></body></html>",
                "https://source.example/search?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("沉默的荣耀",
                result.get(0).getAsJsonObject().get("vod_name").getAsString());
    }

    @Test
    public void blockedVerificationPageIsNeverParsedAsResults() {
        assertTrue(WebCmsRequestProcess.isBlockedPage(Jsoup.parse(
                "<html><head><title>系统安全验证</title></head>"
                        + "<body>请完成验证后继续访问</body></html>")));
        assertTrue(WebCmsRequestProcess.isBlockedPage(Jsoup.parse(
                "<html><head><title>Just a moment...</title></head>"
                        + "<body>Checking your browser</body></html>")));
        assertFalse(WebCmsRequestProcess.isBlockedPage(Jsoup.parse(
                "<html><body><div class='dialog-title'>系统提示</div>"
                        + "<div>搜索完成</div></body></html>")));
    }

    @Test
    public void homeRecommendationsAreNotSearchResults() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>策驰影视</title></head><body>"
                        + "<ul class='stui-vodlist'><li><a href='/v/fixed.html' "
                        + "title='沉默的荣耀'>沉默的荣耀</a></li></ul>"
                        + "</body></html>", "https://source.example/"),
                "maccms", "沉默的荣耀");

        assertEquals(0, result.size());
    }

    @Test
    public void parsesMatchingCardsFromUnrecognizedThemeAndNormalizesSameHostLinks() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>资源检索</title></head><body>"
                        + "<article class='video-result-card'>"
                        + "<a href='https://source.example/2025/123.html'>"
                        + "<img alt='沉默的荣耀'></a><h3>沉默的荣耀</h3></article>"
                        + "</body></html>",
                "https://source.example/search?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("/2025/123.html",
                result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void acceptsQueryIdentifiedShortRouteAsDetailResult() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<article class='result-card'><a href='/s?id=772' "
                        + "title='沉默的荣耀'>沉默的荣耀</a></article>"
                        + "</body></html>",
                "https://source.example/search?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("/s?id=772",
                result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void fedProfileFallsBackToSearchScopedUnknownThemeCards() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<article class='result-card'>"
                        + "<a href='/detail/772.html' title='沉默的荣耀'>沉默的荣耀</a>"
                        + "</article></body></html>",
                "https://source.example/search.php?searchword=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "fed", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("/detail/772.html",
                result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void doesNotAcceptMatchingCardsHostedByAnotherSite() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<article><a href='https://other.example/v/123.html' "
                        + "title='沉默的荣耀'>沉默的荣耀</a></article>"
                        + "</body></html>",
                "https://source.example/search?wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"),
                "maccms", "沉默的荣耀");

        assertEquals(0, result.size());
    }

    @Test
    public void searchResultsMustContainKeyword() {
        WebCmsRequestProcess process = new WebCmsRequestProcess();
        JsonArray result = process.parseCards(Jsoup.parse(
                "<html><head><title>搜索结果</title></head><body>"
                        + "<div id='searchList'>"
                        + "<div class='item'><a href='/v/right.html' title='沉默的荣耀'>"
                        + "沉默的荣耀</a></div>"
                        + "<div class='item'><a href='/v/wrong.html' title='固定推荐'>"
                        + "固定推荐</a></div>"
                        + "</div></body></html>"), "maccms", "沉默的荣耀");

        assertEquals(1, result.size());
        assertEquals("/v/right.html", result.get(0).getAsJsonObject().get("vod_id").getAsString());
    }
}
