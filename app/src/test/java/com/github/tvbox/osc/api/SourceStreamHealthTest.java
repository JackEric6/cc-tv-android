package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.bean.SourceBean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SourceStreamHealthTest {
    private SourceBean xgzy;
    private SourceBean fast;
    private SourceBean slow;
    private SourceBean unknown;

    @Before
    public void setUp() {
        SourceStreamHealth.clear();
        xgzy = source("xgzy");
        fast = source("fast");
        slow = source("slow");
        unknown = source("unknown");
    }

    @After
    public void tearDown() {
        SourceStreamHealth.clear();
    }

    @Test
    public void playbackLatencySortsOnlyMeasuredSourcesAndPinsFirst() {
        SourceStreamHealth.recordSuccess("slow", 900, 1_000L);
        SourceStreamHealth.recordSuccess("fast", 100, 1_000L);

        List<SourceBean> result = SourceStreamHealth.order(
                Arrays.asList(slow, unknown, xgzy, fast), 1_001L);

        assertEquals(Arrays.asList("xgzy", "fast", "slow", "unknown"), keys(result));
    }

    @Test
    public void failureIsDegradedWithExponentialBackoffAndExpires() {
        SourceStreamHealth.recordFailure("fast", 1_000L);
        SourceStreamHealth.recordFailure("fast", 2_000L);
        assertEquals(62_000L, SourceStreamHealth.cooldownUntil("fast"));
        assertEquals(Arrays.asList("slow", "unknown", "fast"), keys(
                SourceStreamHealth.order(Arrays.asList(slow, unknown, fast), 2_001L)));

        assertEquals(Arrays.asList("slow", "unknown", "fast"), keys(
                SourceStreamHealth.order(Arrays.asList(slow, unknown, fast),
                        2_000L + SourceStreamHealth.FAILURE_TTL_MS + 1L)));
    }

    @Test
    public void noFreshMeasurementKeepsConfiguredOrder() {
        assertEquals(Arrays.asList("xgzy", "slow", "unknown"), keys(
                SourceStreamHealth.order(Arrays.asList(slow, unknown, xgzy), 10_000L)));
    }

    @Test
    public void staleSuccessReturnsToStableConfiguredOrder() {
        SourceStreamHealth.recordSuccess("slow", 100, 1_000L);
        assertEquals(Arrays.asList("unknown", "slow"), keys(
                SourceStreamHealth.order(Arrays.asList(unknown, slow),
                        1_000L + SourceStreamHealth.SUCCESS_TTL_MS + 1L)));
    }

    private static SourceBean source(String key) {
        SourceBean source = new SourceBean();
        source.setKey(key);
        source.setName(key);
        return source;
    }

    private static List<String> keys(List<SourceBean> sources) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (SourceBean source : sources) keys.add(source.getKey());
        return keys;
    }
}
