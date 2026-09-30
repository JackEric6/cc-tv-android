package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeFanSourceTest {

    @Test
    public void removedSourcesAreNoLongerExposed() {
        assertFalse(NativeFanSource.isSupported("比特"));
        assertFalse(NativeFanSource.isSupported("热播"));
        assertFalse(NativeFanSource.isSupported("剧圈"));
    }

    @Test
    public void sourceNamesDoNotAliasHotOrBitToJianpian() {
        assertEquals("fan_jianpian", NativeFanSource.normalizeKey("荐片"));
        assertFalse(NativeFanSource.normalizeKey("热播").equals("fan_jianpian"));
        assertFalse(NativeFanSource.normalizeKey("比特").equals("fan_jianpian"));
    }

    @Test
    public void newSourcesAreHandledByTheNativeAdapter() {
        assertEquals("fan_guangying", NativeFanSource.normalizeKey("光影"));
        assertEquals("fan_libbo", NativeFanSource.normalizeKey("立播"));
        assertTrue(NativeFanSource.isSupported("fan_guangying"));
        assertTrue(NativeFanSource.isSupported("fan_libbo"));
    }

    @Test
    public void nomiSearchTitleRemovesCategoryPrefixAndSuffix() {
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("国产剧 庆余年"));
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("庆余年-国产剧"));
        assertEquals("庆余年", NativeFanSource.cleanNomiTitle("庆余年"));
    }
}
