package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ApiConfigBundledSourcesTest {
    @Test
    public void removesRetiredDefaultSourcesButKeepsCanonicalQuantumAndFeifan() {
        JsonArray sites = new JsonArray();
        for (String key : new String[]{
                "beike", "yzzy", "qiumu", "sony", "fan_guangying", "fan_libbo",
                "cms_liangzi", "cms_feifan", "cms_haiwaikan", "cms_suoni",
                "cms_kuaifan", "cms_leshi", "lzi", "ffzy", "uuzy", "cms_baofeng"
        }) {
            JsonObject site = new JsonObject();
            site.addProperty("key", key);
            sites.add(site);
        }

        ApiConfig.removeRetiredDefaultSources(sites);

        Set<String> remaining = new HashSet<>();
        for (int index = 0; index < sites.size(); index++) {
            remaining.add(sites.get(index).getAsJsonObject().get("key").getAsString());
        }
        assertEquals(4, remaining.size());
        assertTrue(remaining.contains("lzi"));
        assertTrue(remaining.contains("ffzy"));
        assertTrue(remaining.contains("uuzy"));
        assertTrue(remaining.contains("cms_baofeng"));
        assertFalse(remaining.contains("sony"));
    }
}
