package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class IkanbotCmsParserTest {
    @Test
    public void buildTokenMatchesWebPageAlgorithm() {
        assertEquals("a96bb64855046b9b1798b0e3d45c348e",
                IkanbotCmsParser.buildToken("1016582", "ja96bb648j0p55046b9blkg1798b0e3bvtd45c348e"));
    }

    @Test
    public void parsePlaySourcesKeepsOnlyHlsEpisodes() {
        String response = "{\"state\":1,\"data\":{\"list\":["
                + "{\"resData\":\"[{\\\"flag\\\":\\\"m3u8\\\",\\\"url\\\":\\\"第1集$https://a.test/1.m3u8#坏链接$https://a.test/1.mp4\\\"}]\"}"
                + "]}}";
        List<IkanbotCmsParser.PlaySource> sources = IkanbotCmsParser.parsePlaySources(response);
        assertEquals(1, sources.size());
        assertEquals("m3u8", sources.get(0).name);
        assertEquals(1, sources.get(0).episodes.size());
        assertTrue(sources.get(0).episodes.get(0).url.endsWith("1.m3u8"));
    }
}
