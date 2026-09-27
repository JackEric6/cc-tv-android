package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;

public class XgzyRequestProcessTest {
    @Test
    public void mergesChildResultsInSuppliedOrderAndCapsThePage() {
        JsonObject first = categoryResult(35, "first", 15);
        JsonObject second = categoryResult(25, "second", 15);

        JsonObject result = XgzyRequestProcess.mergeChildCategoryResults(
                Arrays.asList(first, second), "1");
        JsonArray list = result.getAsJsonArray("list");

        assertEquals(20, list.size());
        assertEquals("first-0", list.get(0).getAsJsonObject().get("vod_id").getAsString());
        assertEquals("second-4", list.get(19).getAsJsonObject().get("vod_id").getAsString());
        assertEquals(60, result.get("total").getAsInt());
        assertEquals(3, result.get("pagecount").getAsInt());
    }

    @Test
    public void mergeKeepsTheFirstChildClassList() {
        JsonObject first = categoryResult(1, "first", 1);
        JsonArray classes = new JsonArray();
        JsonObject movie = new JsonObject();
        movie.addProperty("type_name", "电影");
        classes.add(movie);
        first.add("class", classes);
        JsonObject second = categoryResult(1, "second", 1);

        JsonObject result = XgzyRequestProcess.mergeChildCategoryResults(
                Arrays.asList(first, second), "1");

        assertEquals("电影", result.getAsJsonArray("class").get(0)
                .getAsJsonObject().get("type_name").getAsString());
    }

    private JsonObject categoryResult(int total, String prefix, int count) {
        JsonArray list = new JsonArray();
        for (int index = 0; index < count; index++) {
            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", prefix + "-" + index);
            list.add(vod);
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.add("list", list);
        return result;
    }
}
