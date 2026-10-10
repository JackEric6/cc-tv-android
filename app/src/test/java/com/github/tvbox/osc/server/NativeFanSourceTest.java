package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.junit.Test;

public class NativeFanSourceTest {

    @Test
    public void removedSourcesAreNoLongerExposed() {
        assertFalse(NativeFanSource.isSupported("比特"));
        assertFalse(NativeFanSource.isSupported("热播"));
        assertFalse(NativeFanSource.isSupported("剧圈"));
    }

    @Test
    public void sourceNamesDoNotAliasHotOrBitToJianpian() {
        assertEquals("fan_jianpian", NativeFanSource.normalizeKey("荐片"));
        assertFalse(NativeFanSource.normalizeKey("热播").equals("fan_jianpian"));
        assertFalse(NativeFanSource.normalizeKey("比特").equals("fan_jianpian"));
    }

    @Test
    public void newSourcesAreHandledByTheNativeAdapter() {
        assertEquals("fan_guangying", NativeFanSource.normalizeKey("光影"));
        assertEquals("fan_libbo", NativeFanSource.normalizeKey("立播"));
        assertTrue(NativeFanSource.isSupported("fan_guangying"));
        assertTrue(NativeFanSource.isSupported("fan_libbo"));
    }

    @Test
    public void nomiSearchTitleRemovesCategoryPrefixAndSuffix() {
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("国产剧 庆余年"));
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("庆余年-国产剧"));
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("庆余年"));
    }

    @Test
    public void nomiSearchUsesCardTitleAndDisplaysEpisodeYearAndArea() {
        String json = NativeFanSource.nomiPage(Jsoup.parse(
                "<div class='search-item'>"
                        + "<a href='//vod-detail-id-63541.html'><img "
                        + "data-original='https://img.example/63541.jpg'></a>"
                        + "<a href='//vod-detail-id-63541.html' "
                        + "title='/vod-detail-id-63541.html'></a>"
                        + "<h3 class='vod-title'>沉默的荣耀</h3>"
                        + "<span class='vod-remarks'>全39集</span>"
                        + "<span class='vod-year'>2025</span>"
                        + "<span class='vod-area'>大陆</span>"
                        + "</div>"));

        JsonObject result = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("/vod-detail-id-63541.html", result.get("vod_id").getAsString());
        assertEquals("沉默的荣耀", result.get("vod_name").getAsString());
        assertEquals("全39集", result.get("vod_remarks").getAsString());
        assertEquals("2025", result.get("vod_year").getAsString());
        assertEquals("大陆", result.get("vod_area").getAsString());
    }

    @Test
    public void nomiSearchDoesNotUsePlayActionAsTitle() {
        String json = NativeFanSource.nomiPage(Jsoup.parse(
                "<div class='search-item'>"
                        + "<a href='/vod-detail-id-63541.html'><img alt='沉默的荣耀' "
                        + "src='https://img.example/63541.jpg'></a>"
                        + "<a href='/vod-detail-id-63541.html'>立即播放</a>"
                        + "</div>"));

        JsonObject result = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("沉默的荣耀", result.get("vod_name").getAsString());
    }

    @Test
    public void nomiPlaySourcesKeepEachRouteEpisodesSeparate() {
        JsonObject result = NativeFanSource.nomiPlaySources(Jsoup.parse(
                "<div id='leftTabBox'>"
                        + "<ul class='playfrom'><li>糯米线路</li><li>备用线路</li></ul>"
                        + "<div class='numList'><ul>"
                        + "<li><a href='/vod-play-id-63541-src-1-num-1.html'>第1集</a></li>"
                        + "<li><a href='/vod-play-id-63541-src-1-num-2.html'>第2集</a></li>"
                        + "</ul></div>"
                        + "<div class='numList'><ul>"
                        + "<li><a href='/vod-play-id-63541-src-2-num-1.html'>第1集</a></li>"
                        + "</ul></div></div>"));

        assertEquals("糯米线路$$$备用线路", result.get("vod_play_from").getAsString());
        String[] groups = result.get("vod_play_url").getAsString().split("\\$\\$\\$");
        assertEquals(2, groups.length);
        assertTrue(groups[0].contains("src-1-num-1.html"));
        assertTrue(groups[0].contains("src-1-num-2.html"));
        assertTrue(groups[0].indexOf("src-1-num-2.html") < groups[0].indexOf("src-1-num-1.html"));
        assertFalse(groups[0].contains("src-2-"));
        assertTrue(groups[1].contains("src-2-num-1.html"));
        assertFalse(groups[1].contains("src-1-"));
    }
}
