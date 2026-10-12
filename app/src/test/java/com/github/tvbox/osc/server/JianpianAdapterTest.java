package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

public class JianpianAdapterTest {
    @Test
    public void homeCatalogExposesJianpianCategories() {
        JsonObject home = JianpianAdapter.homeCatalog();
        JsonArray categories = home.getAsJsonArray("class");

        assertEquals(4, categories.size());
        assertEquals("2", categories.get(0).getAsJsonObject().get("type_id").getAsString());
        assertEquals("电视剧", categories.get(0).getAsJsonObject().get("type_name").getAsString());
        assertEquals("1", categories.get(1).getAsJsonObject().get("type_id").getAsString());
        assertEquals("4", categories.get(2).getAsJsonObject().get("type_id").getAsString());
        assertEquals("3", categories.get(3).getAsJsonObject().get("type_id").getAsString());
        assertTrue(home.getAsJsonArray("list").isEmpty());
    }

    @Test
    public void mapsCategoryResultsAndPaginationToCmsFields() {
        JsonObject source = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("id", 321);
        item.addProperty("title", "热播剧");
        items.add(item);
        source.add("data", items);
        source.addProperty("total", 45);

        JsonObject result = JianpianAdapter.mapSearchResult(source, 2, 20);

        assertEquals(2, result.get("page").getAsInt());
        assertEquals(3, result.get("pagecount").getAsInt());
        assertEquals(45, result.get("total").getAsInt());
        assertEquals("321", result.getAsJsonArray("list").get(0)
                .getAsJsonObject().get("vod_id").getAsString());
    }

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
