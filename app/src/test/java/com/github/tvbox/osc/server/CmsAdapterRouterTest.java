package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CmsAdapterRouterTest {
    @Test
    public void routesKnownSourcesByKeyOrApiHost() {
        assertEquals(CmsAdapterRouter.XML_RSS,
                CmsAdapterRouter.adapterId("yzzy", "https://example.com/api.php/provide/vod/"));
        assertEquals(CmsAdapterRouter.XML_RSS,
                CmsAdapterRouter.adapterId("", "https://api.yzzy-api.com/api.php/provide/vod/"));
        assertEquals(CmsAdapterRouter.YPARSE_JSON,
                CmsAdapterRouter.adapterId("", "https://api.yparse.com/api/json"));
        assertEquals(CmsAdapterRouter.MAOTAI_JSON,
                CmsAdapterRouter.adapterId("", "https://caiji.maotai999.vip/api.php/provide/vod/"));
        assertEquals(CmsAdapterRouter.IKUN_JSON,
                CmsAdapterRouter.adapterId("ikunzy", "https://example.com/api"));
    }

    @Test
    public void unknownSourcesKeepStandardCompatibilityAdapter() {
        assertEquals(CmsAdapterRouter.STANDARD_JSON,
                CmsAdapterRouter.adapterId("new-source", "https://new.example/api"));
        assertEquals(CmsAdapterRouter.STANDARD_JSON,
                CmsAdapterRouter.resolve("new-source", "https://new.example/api").id());
    }

    @Test
    public void dedicatedPathExtractsSourceKeyWithoutForwardingIt() {
        Map<String, String> params = new HashMap<>();
        params.put("source", "https://example.com/api");
        assertTrue(CmsAdapterRouter.isDedicatedPath("/cms/sony"));
        assertTrue(CmsAdapterRouter.isDedicatedPath("/cms"));
        assertEquals("sony", DedicatedCmsRequestProcess.sourceKey("/cms/sony", params));
        params.put("sourceKey", "explicit");
        assertEquals("explicit", DedicatedCmsRequestProcess.sourceKey("/cms/sony", params));
    }
}
