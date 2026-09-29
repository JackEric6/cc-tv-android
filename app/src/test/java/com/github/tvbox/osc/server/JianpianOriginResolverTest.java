package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class JianpianOriginResolverTest {
    @Test
    public void parsesDiscoveryHostsAndRemovesDuplicates() {
        List<String> origins = JianpianOriginResolver.parseDiscovery(
                "cqjdn.com, https://BDGNBRWS.com/\n cqjdn.com");
        assertEquals(2, origins.size());
        assertEquals("https://cqjdn.com", origins.get(0));
        assertEquals("https://bdgnbrws.com", origins.get(1));
    }

    @Test
    public void candidatesPutKnownGoodOriginFirstAndKeepFallbacks() {
        List<String> origins = JianpianOriginResolver.candidates(
                "https://preferred.example", "https://discovered.example");
        assertEquals("https://preferred.example", origins.get(0));
        assertEquals("https://discovered.example", origins.get(1));
        assertTrue(origins.contains("https://jianpian.com"));
        assertTrue(origins.contains("https://jianpian25.com"));
        assertTrue(origins.contains("https://hodpj.cc"));
    }

    @Test
    public void failoverIncludesDeadEndpointResponses() {
        assertTrue(JianpianOriginResolver.shouldFailover(404));
        assertTrue(JianpianOriginResolver.shouldFailover(503));
        assertFalse(JianpianOriginResolver.shouldFailover(401));
        assertFalse(JianpianOriginResolver.shouldFailover(422));
    }
}
