package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeFanSourceTest {

    @Test
    public void latestChineseKeysUseTheirOwnNativeAdapters() {
        assertEquals("fan_bit", NativeFanSource.normalizeKey("比特"));
        assertEquals("fan_hot", NativeFanSource.normalizeKey("热播"));
        assertEquals("fan_juquan", NativeFanSource.normalizeKey("剧圈"));
        assertTrue(NativeFanSource.isSupported("比特"));
        assertTrue(NativeFanSource.isSupported("热播"));
        assertTrue(NativeFanSource.isSupported("剧圈"));
    }

    @Test
    public void sourceNamesDoNotAliasHotOrBitToJianpian() {
        assertEquals("fan_jianpian", NativeFanSource.normalizeKey("荐片"));
        assertFalse(NativeFanSource.normalizeKey("热播").equals("fan_jianpian"));
        assertFalse(NativeFanSource.normalizeKey("比特").equals("fan_jianpian"));
    }
}
