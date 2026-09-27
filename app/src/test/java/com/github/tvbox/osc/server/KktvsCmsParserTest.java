package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

public class KktvsCmsParserTest {
    @Test
    public void keepsPlayerLinesAndMovesHlsFirst() {
        String json = "{\"list\":[{"
                + "\"vod_play_from\":\"hxplayer$$$old$$$2mplayer$$$good\","
                + "\"vod_play_url\":\"01$https://ok.test/hx/index.m3u8$$$01$https://ok.test/video.mp4$$$01$https://ok.test/2m.mp4?sign=1$$$01$https://ok.test/video/index.m3u8\","
                + "\"vod_play_server\":\"a$$$b$$$c$$$d\""
                + "}]}";
        String normalized = KktvsCmsParser.normalize(json);
        String from = JsonParser.parseString(normalized).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject().get("vod_play_from").getAsString();
        assertEquals("hxplayer$$$good$$$old$$$2mplayer", from);
        assertTrue(normalized.contains("https://ok.test/video/index.m3u8"));
        assertTrue(normalized.contains("hxplayer"));
        assertTrue(normalized.contains("2mplayer"));
        assertTrue(normalized.contains("https://ok.test/2m.mp4?sign=1"));
    }

    @Test
    public void unwrapsPlayerPageMediaParameter() {
        String json = "{\"list\":[{" 
                + "\"vod_play_from\":\"2mplayer$$$hxplayer\","
                + "\"vod_play_url\":\"01$https://www.kktvs.com/vod/lx1/?url=https%3A%2F%2Fvideo.test%2Ftwo.mp4$$$01$https://video.test/one.m3u8\""
                + "}]}";
        String normalized = KktvsCmsParser.normalize(json);
        String urls = JsonParser.parseString(normalized).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject().get("vod_play_url").getAsString();
        assertEquals("01$https://video.test/one.m3u8$$$01$https://video.test/two.mp4", urls);
    }

    @Test
    public void normalizesEachEpisodeInHxAndTwoMPlayerLines() {
        String json = "{\"list\":[{" 
                + "\"vod_play_from\":\"hxplayer$$$2mplayer\","
                + "\"vod_play_url\":\"01$//hls.test/one.m3u8#02$//hls.test/two.m3u8$$$01$https://video.test/one.mp4#02$https://video.test/two.mp4\""
                + "}]}";
        String normalized = KktvsCmsParser.normalize(json);
        String urls = JsonParser.parseString(normalized).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject().get("vod_play_url").getAsString();
        assertEquals("01$https://hls.test/one.m3u8#02$https://hls.test/two.m3u8$$$01$https://video.test/one.mp4#02$https://video.test/two.mp4", urls);
    }

    @Test
    public void keepsExistingOrderForEqualHlsPriority() {
        String json = "{\"list\":[{"
                + "\"vod_play_from\":\"a$$$b\","
                + "\"vod_play_url\":\"01$https://a.test/a.m3u8$$$01$https://b.test/b.m3u8\""
                + "}]}";
        String normalized = KktvsCmsParser.normalize(json);
        String from = JsonParser.parseString(normalized).getAsJsonObject()
                .getAsJsonArray("list").get(0).getAsJsonObject().get("vod_play_from").getAsString();
        assertEquals("a$$$b", from);
    }

    @Test
    public void extractsMediaFromHxAndTwoMPlayerPagePayloads() {
        String body = "<script>var player_aaaa={\"url\":\"https:\\/\\/media.test\\/hx\\/index.m3u8?token=1\"};</script>";
        assertEquals("https://media.test/hx/index.m3u8?token=1",
                KktvsCmsParser.extractMediaUrl(body, "https://www.kktvs.com/v/1-1-1.html"));
        assertEquals("https://media.test/two.m3u8",
                KktvsCmsParser.extractMediaUrl("{\"playurl\":\"https://media.test/two.m3u8\"}", ""));
    }
}
