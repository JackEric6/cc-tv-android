package com.github.tvbox.osc.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

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
}
