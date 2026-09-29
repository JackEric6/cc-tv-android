package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.tvbox.osc.bean.SourceBean;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

public class CmsRequestProcessTest {
    @Test
    public void searchableTypeIdsSkipsParentsAndPreservesLeafOrder() {
        JsonArray classes = new JsonArray();
        classes.add(category("1", "电影", "0"));
        classes.add(category("2", "电视剧", "0"));
        classes.add(category("6", "动作片", "1"));
        classes.add(category("13", "国产剧", "2"));
        classes.add(category("6", "重复动作片", "1"));
        classes.add(category("54", "短剧", "0"));

        assertEquals(Arrays.asList("6", "13", "54"),
                CmsRequestProcess.searchableTypeIds(classes));
    }

    @Test
    public void searchableTypeIdsKeepsFlatCategoryIdsWithoutHierarchy() {
        JsonArray classes = new JsonArray();
        classes.add(category("1", "国产剧"));
        classes.add(category("2", "喜剧片"));

        assertEquals(Arrays.asList("1", "2"), CmsRequestProcess.searchableTypeIds(classes));
    }

    @Test
    public void requestUrlOverridesExistingCmsQueryParameters() {
        Map<String, String> params = new HashMap<>();
        params.put("ac", "list");
        params.put("pg", "2");
        params.put("wd", "沉默的荣耀");
        params.put("t", "6");

        String url = CmsRequestProcess.buildRequestUrl(
                "https://example.com/api.php/provide/vod/?ac=detail&pg=9&wd=old&t=1&token=x",
                params);

        assertTrue(url.contains("ac=list"));
        assertTrue(url.contains("pg=2"));
        assertTrue(url.contains("wd=%E6%B2%89%E9%BB%98%E7%9A%84%E8%8D%A3%E8%80%80"));
        assertTrue(url.contains("t=6"));
        assertTrue(!url.contains("ac=detail") && !url.contains("pg=9")
                && !url.contains("wd=old") && !url.contains("t=1"));
    }

    @Test
    public void sourceCandidatesAppendsConfiguredBackupsAfterPrimaryAndDeduplicates() {
        SourceBean source = new SourceBean();
        source.setApi("https://backup-test.example/api.php/provide/vod/");
        source.setBackupApi(Arrays.asList(
                "https://backup-test.example/api.php/provide/vod/",
                "https://backup-api.example/api.php/provide/vod/",
                "https://backup-api.example/api.php/provide/vod/"));
        source.setBackupDomain(Arrays.asList(
                "https://backup-domain.example/",
                "https://backup-domain.example/"));

        java.util.List<String> candidates = new CmsRequestProcess().sourceCandidates(source.getApi());

        assertEquals("https://backup-test.example/api.php/provide/vod/", candidates.get(0));
        assertEquals("https://backup-api.example/api.php/provide/vod/", candidates.get(1));
        assertEquals("https://backup-domain.example/api.php/provide/vod/", candidates.get(2));
        assertEquals(3, candidates.size());
    }

    @Test
    public void searchableTypeIdsFallsBackFromSelfParentMetadata() {
        JsonArray classes = new JsonArray();
        JsonObject item = category("6", "国产剧");
        item.addProperty("type_id_1", "6");
        classes.add(item);

        assertEquals(Arrays.asList("6"), CmsRequestProcess.searchableTypeIds(classes));
    }

    @Test
    public void imageHydrationUsesExecutorSeparateFromSearchPool() throws Exception {
        Field searchPool = CmsRequestProcess.class.getDeclaredField("SEARCH_POOL");
        Field imagePool = CmsRequestProcess.class.getDeclaredField("IMAGE_DETAIL_POOL");
        searchPool.setAccessible(true);
        imagePool.setAccessible(true);
        assertTrue(searchPool.get(null) instanceof ExecutorService);
        assertTrue(imagePool.get(null) instanceof ExecutorService);
        assertTrue(searchPool.get(null) != imagePool.get(null));
    }

    @Test
    public void ikunSearchUsesItsDedicatedApiAndFallbackCandidates() {
        java.util.List<String> candidates = new CmsRequestProcess().sourceCandidates(
                "https://ikunzyapi.com/api.php/provide/vod");

        assertTrue(candidates.contains(
                "https://ikunzyapi.com/api.php/provide/vod/from/ikm3u8/at/json"));
        assertTrue(candidates.contains(
                "https://ikunzyapi.com/api.php/provide/vod/"));
    }

    @Test
    public void detailRequestsDoNotSynchronouslyHydrateImages() {
        assertTrue(!CmsRequestProcess.shouldHydrateImages("detail", "关键词"));
        assertTrue(!CmsRequestProcess.shouldHydrateImages("detail", ""));
        assertTrue(CmsRequestProcess.shouldHydrateImages("list", ""));
    }

    @Test
    public void convertsYzzyXmlToStandardCmsJson() throws Exception {
        String xml = "<rss page=\"2\" pagecount=\"4\" limit=\"20\" recordcount=\"1\">"
                + "<class><ty id=\"6\"><![CDATA[动作片]]></ty></class>"
                + "<list page=\"2\"><video><id>42</id><name><![CDATA[测试影片]]></name>"
                + "<type>电影</type><note>高清</note><last>2026-09-24</last><dt>m3u8</dt>"
                + "<pic>https://img.example/p.jpg</pic><actor>演员甲</actor>"
                + "<director>导演乙</director><description><![CDATA[影片简介]]></description>"
                + "<url>第1集$https://play.example/1.m3u8</url></video></list></rss>";

        JsonObject result = CmsRequestProcess.convertYzzyXml(xml);

        assertEquals("2", result.get("page").getAsString());
        assertEquals("1", result.get("total").getAsString());
        assertEquals("6", result.getAsJsonArray("class").get(0)
                .getAsJsonObject().get("type_id").getAsString());
        JsonObject video = result.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("42", video.get("vod_id").getAsString());
        assertEquals("测试影片", video.get("vod_name").getAsString());
        assertEquals("影片简介", video.get("vod_content").getAsString());
        assertEquals("https://img.example/p.jpg", video.get("vod_pic").getAsString());
        assertTrue(video.get("vod_play_url").getAsString().contains("https://play.example/1.m3u8"));
    }

    @Test
    public void upgradesXinlangImageHostWithoutTouchingOtherHosts() {
        assertEquals("https://xl.xltupian.com/a.jpg?x=1",
                CmsRequestProcess.normalizeImageUrl("https://xinlangtupian.com/a.jpg?x=1"));
        assertEquals("https://other.example/xinlangtupian.com/a.jpg?x=1",
                CmsRequestProcess.normalizeImageUrl("https://other.example/xinlangtupian.com/a.jpg?x=1"));
        assertEquals("https://xl.xltupian.com/a.jpg?x=1",
                CmsRequestProcess.normalizeImageUrl("//xinlangtupian.com/a.jpg?x=1"));
    }

    @Test
    public void upgradesKnownPollutedImageHosts() {
        assertEquals("https://img.lzipic.com/a.jpg?x=1",
                CmsRequestProcess.normalizeImageUrl("https://viptulz.com/a.jpg?x=1"));
        assertEquals("https://img.lzipic.com/a.jpg?x=1",
                CmsRequestProcess.normalizeImageUrl("https://img.lzzyimg.com/a.jpg?x=1"));
    }

    @Test
    public void cleansBlankMalformedAndWrappedImageUrls() {
        assertEquals("", CmsRequestProcess.normalizeImageUrl(null));
        assertEquals("", CmsRequestProcess.normalizeImageUrl(" \n\t "));
        assertEquals("https://img.example/a.jpg",
                CmsRequestProcess.normalizeImageUrl("  https:/img.example/a.jpg \n"));
        assertEquals("https://img.example/a.jpg",
                CmsRequestProcess.normalizeImageUrl("\"//img.example/a.jpg\""));
    }

    @Test
    public void decodesHtmlEntitiesAndJsonImageEscapes() {
        assertEquals("https://img.example/a.jpg?x=1&y=2",
                CmsRequestProcess.normalizeImageUrl(
                        "  \"https:\\/\\/img.example\\/a.jpg?x=1&amp;y=2\"\n"));
        assertEquals("https://img.example/a.jpg?x=1&y=2",
                CmsRequestProcess.normalizeImageUrl(
                        "https" + "\\u003a" + "\\/\\/img.example\\/a.jpg?x=1" + "\\u0026y=2"));
    }

    @Test
    public void normalizesNestedAndRelativePosterAliasesUsingCurrentSource() {
        JsonObject vod = new JsonObject();
        JsonObject cover = new JsonObject();
        cover.addProperty("original", "/upload/poster.jpg");
        vod.add("cover", cover);

        CmsRequestProcess.normalizeImageAliases(vod,
                "https://api.example.com/api.php/provide/vod/");

        assertEquals("https://api.example.com/upload/poster.jpg",
                vod.get("vod_pic").getAsString());
    }

    @Test
    public void normalizesArrayPosterAliasWithoutCrossSourceLookup() {
        JsonObject vod = new JsonObject();
        JsonArray images = new JsonArray();
        images.add("//img.example.com/poster.webp");
        vod.add("image", images);

        CmsRequestProcess.normalizeImageAliases(vod, "https://api.example.com/vod");

        assertEquals("https://img.example.com/poster.webp",
                vod.get("vod_pic").getAsString());
    }

    @Test
    public void normalizesArrayAndAliasPlaybackFields() {
        JsonObject vod = new JsonObject();
        JsonArray flags = new JsonArray();
        flags.add("jlm3u8");
        flags.add("jlplayer");
        JsonArray urls = new JsonArray();
        urls.add("播放$https://media.example/one.m3u8");
        urls.add("播放$https://media.example/two.html");
        vod.add("playFrom", flags);
        vod.add("playUrl", urls);

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("jlm3u8$$$jlplayer", vod.get("vod_play_from").getAsString());
        assertEquals("播放$https://media.example/one.m3u8$$$播放$https://media.example/two.html",
                vod.get("vod_play_url").getAsString());
    }

    @Test
    public void normalizesObjectPlaybackFieldsWithoutDroppingPlayableLine() {
        JsonObject vod = new JsonObject();
        JsonObject urls = new JsonObject();
        urls.addProperty("jlm3u8", "第1集$https://media.example/one.m3u8");
        urls.addProperty("jlplayer", "第1集$https://media.example/two.html");
        vod.add("vod_play_url", urls);

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("jlm3u8$$$jlplayer", vod.get("vod_play_from").getAsString());
        assertTrue(vod.get("vod_play_url").getAsString().contains("https://media.example/one.m3u8"));
        assertTrue(vod.get("vod_play_url").getAsString().contains("https://media.example/two.html"));
    }

    @Test
    public void alignsCommaSeparatedPlaybackFlagsWithDollarSeparatedGroups() {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_play_from", "jlm3u8,jlplayer");
        vod.addProperty("vod_play_url",
                "第1集$https://media.example/direct.m3u8$$$第1集$https://juliang.app/player?id=1");

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("jlm3u8$$$jlplayer", vod.get("vod_play_from").getAsString());
        assertEquals(2, vod.get("vod_play_url").getAsString().split("\\$\\$\\$", -1).length);
    }

    @Test
    public void alignsDollarSeparatedPlaybackFlagsWithCommaSeparatedGroups() {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_play_from", "jlm3u8$$$jlplayer");
        vod.addProperty("vod_play_url",
                "第1集$https://media.example/direct.m3u8,第1集$https://juliang.app/player?id=1");

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("jlm3u8$$$jlplayer", vod.get("vod_play_from").getAsString());
        assertEquals(2, vod.get("vod_play_url").getAsString().split("\\$\\$\\$", -1).length);
    }

    @Test
    public void juliangDetailKeepsDirectAndWebPlaybackLines() {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", "629691024490364933");
        vod.addProperty("vod_name", "失忆医生");
        vod.addProperty("vod_play_from", "jlm3u8$$$jlplayer");
        vod.addProperty("vod_play_url",
                "第1集$https://jimaoys94.com/public/playback/episode/smart.m3u8$$$"
                        + "第1集$https://jimaoys95.com/s/episode");

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("629691024490364933", vod.get("vod_id").getAsString());
        assertEquals("jlm3u8$$$jlplayer", vod.get("vod_play_from").getAsString());
        assertTrue(vod.get("vod_play_url").getAsString().contains("smart.m3u8"));
        assertTrue(vod.get("vod_play_url").getAsString().contains("jimaoys95.com/s/episode"));
    }

    @Test
    public void sourceSpecificPlaybackFiltersKeepOnlyDocumentedLine() {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_name", "兰香如故");
        vod.addProperty("vod_play_from", "subm3u8$$$subplayer");
        vod.addProperty("vod_play_url", "第1集$https://cdn.example/a.m3u8$$$第1集$https://site.example/player");
        CmsRequestProcess.normalizePlaybackFields(vod,
                "https://subocj.com/api.php/provide/vod/");
        assertEquals("subm3u8", vod.get("vod_play_from").getAsString());
        assertEquals("第1集$https://cdn.example/a.m3u8", vod.get("vod_play_url").getAsString());
        assertEquals("兰香如故", vod.get("vod_name").getAsString());

        JsonObject juliang = new JsonObject();
        juliang.addProperty("vod_play_from", "jlplayer$$$jlm3u8");
        juliang.addProperty("vod_play_url", "第1集$https://site.example/player$$$第1集$https://cdn.example/jl.m3u8");
        CmsRequestProcess.normalizePlaybackFields(juliang,
                "https://api.juliang.live/api/provide/vod/");
        assertEquals("jlm3u8", juliang.get("vod_play_from").getAsString());
        assertTrue(juliang.get("vod_play_url").getAsString().contains("jl.m3u8"));

        assertEquals("ukm3u8", CmsRequestProcess.requiredPlaybackFlag(
                "https://api.ukuapi88.com/api.php/provide/vod/"));
    }

    @Test
    public void yparseDetailKeepsResolverTokenAsSinglePlaybackGroup() {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", "453727");
        vod.addProperty("vod_name", "我不是大师");
        vod.addProperty("vod_play_from", "yparse");
        vod.addProperty("vod_play_url", "第01集$Yparse95UFjD97o000ojCa5TyR7eT15ssWiBCg");

        CmsRequestProcess.normalizePlaybackFields(vod);

        assertEquals("453727", vod.get("vod_id").getAsString());
        assertEquals("yparse", vod.get("vod_play_from").getAsString());
        assertEquals("第01集$Yparse95UFjD97o000ojCa5TyR7eT15ssWiBCg",
                vod.get("vod_play_url").getAsString());
    }

    @Test
    public void clearsYzzyDetailWhenRequestedIdsAreMissing() {
        JsonObject result = resultWithVideo("42");
        Map<String, String> params = params("detail", "100, 101");

        CmsRequestProcess.guardYzzyDetailResult(
                "https://api.yzzy-api.com/api.php/provide/vod/", params, result);

        assertEquals(0, result.getAsJsonArray("list").size());
    }

    @Test
    public void clearsYzzyDetailWhenListContainsUnrequestedId() {
        JsonObject result = resultWithVideo("42");
        result.getAsJsonArray("list").add(video("43"));
        Map<String, String> params = params("detail", "100, 42");

        CmsRequestProcess.guardYzzyDetailResult(
                "https://api.yzzy-api.com/api.php/provide/vod/", params, result);

        assertEquals(0, result.getAsJsonArray("list").size());
    }

    @Test
    public void keepsYzzyDetailWhenAllReturnedIdsAreRequested() {
        JsonObject result = resultWithVideo("42");
        Map<String, String> params = params("detail", "100, 42");

        CmsRequestProcess.guardYzzyDetailResult(
                "https://api.yzzy-api.com/api.php/provide/vod/", params, result);

        assertEquals(1, result.getAsJsonArray("list").size());
        assertEquals("42", result.getAsJsonArray("list").get(0)
                .getAsJsonObject().get("vod_id").getAsString());
    }

    @Test
    public void keepsYzzyDetailWhenSingleRequestedIdMatches() {
        JsonObject result = resultWithVideo("42");
        Map<String, String> params = params("detail", "42");

        CmsRequestProcess.guardYzzyDetailResult(
                "https://api.yzzy-api.com/api.php/provide/vod/", params, result);

        assertEquals(1, result.getAsJsonArray("list").size());
        assertEquals("42", result.getAsJsonArray("list").get(0)
                .getAsJsonObject().get("vod_id").getAsString());
    }

    private JsonObject resultWithVideo(String id) {
        JsonObject result = new JsonObject();
        JsonObject video = new JsonObject();
        video.addProperty("vod_id", id);
        JsonArray list = new JsonArray();
        list.add(video);
        result.add("list", list);
        return result;
    }

    private JsonObject video(String id) {
        JsonObject video = new JsonObject();
        video.addProperty("vod_id", id);
        return video;
    }

    private Map<String, String> params(String action, String ids) {
        Map<String, String> params = new HashMap<>();
        params.put("ac", action);
        params.put("ids", ids);
        return params;
    }

    private JsonObject category(String id, String name) {
        JsonObject category = new JsonObject();
        category.addProperty("type_id", id);
        category.addProperty("type_name", name);
        return category;
    }

    private JsonObject category(String id, String name, String parentId) {
        JsonObject category = category(id, name);
        category.addProperty("type_pid", parentId);
        return category;
    }
}
