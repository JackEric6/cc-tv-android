package com.github.catvod.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.Dns;

public class OkDnsFallbackTest {
    @Test
    public void usesDoHFallbackAfterConfiguredAndSystemDnsFail() throws Exception {
        List<InetAddress> expected = Collections.singletonList(InetAddress.getByAddress(
                new byte[]{(byte) 203, 0, 113, 8}));
        Dns failing = hostname -> {
            throw new UnknownHostException("not resolved");
        };
        Dns fallback = hostname -> expected;

        List<InetAddress> actual = OkDns.lookupWithFallback(
                "cdn.example", failing, failing, Arrays.asList(fallback));

        assertSame(expected, actual);
    }

    @Test
    public void keepsPrimaryDnsWhenItSucceeds() throws Exception {
        List<InetAddress> expected = Collections.singletonList(InetAddress.getByAddress(
                new byte[]{(byte) 203, 0, 113, 9}));
        Dns primary = hostname -> expected;
        Dns unused = hostname -> {
            throw new UnknownHostException("should not be reached");
        };

        assertEquals(expected, OkDns.lookupWithFallback(
                "cdn.example", primary, unused, Collections.singletonList(unused)));
    }
}
