package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;

import org.junit.Test;

public class JianpianAdapterTest {
    @Test
    public void mapsJianpianWorkFieldsToCmsFields() {
        JsonObject source = new JsonObject();
        source.addProperty("id", 123);
        source.addProperty("title", "沉默的荣耀");
        source.addProperty("poster_url", "https://example.com/poster.jpg");
        source.addProperty("year", 2025);
        source.addProperty("area", "中国大陆");
        source.addProperty("synopsis", "简介");

        JsonObject vod = JianpianAdapter.mapVod(source);

        assertEquals("123", vod.get("vod_id").getAsString());
        assertEquals("沉默的荣耀", vod.get("vod_name").getAsString());
        assertEquals("https://example.com/poster.jpg", vod.get("vod_pic").getAsString());
        assertEquals("2025", vod.get("vod_year").getAsString());
        assertEquals("简介", vod.get("vod_content").getAsString());
        assertEquals("", vod.get("vod_play_url").getAsString());
    }
}
