package com.github.tvbox.osc.server;

import static org.junit.Assert.assertSame;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.util.OkGoHelper;

import org.junit.Test;

import java.lang.reflect.Field;

import okhttp3.OkHttpClient;

public class SourceNetworkRoutingTest {
    @Test
    public void cmsAndXgzyClientsUseTheApplicationProxySelector() throws Exception {
        assertConfiguredClient(CmsRequestProcess.class, "DIRECT_CLIENT");
        assertConfiguredClient(XgzyRequestProcess.class, "DIRECT_CLIENT");
        assertSame(OkGoHelper.proxySelector(), OkHttp.client(30_000L).proxySelector());
    }

    @Test
    public void liveResolverUsesTheApplicationProxySelector() throws Exception {
        Class<?> resolverClass = Class.forName("com.github.tvbox.osc.util.live.YspLiveResolver");
        Object resolver = resolverClass.getField("INSTANCE").get(null);
        Field clientField = resolverClass.getDeclaredField("http");
        clientField.setAccessible(true);
        OkHttpClient client = (OkHttpClient) clientField.get(resolver);

        assertSame(OkGoHelper.proxySelector(), client.proxySelector());
    }

    private void assertConfiguredClient(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        OkHttpClient client = (OkHttpClient) field.get(null);
        assertSame(OkGoHelper.proxySelector(), client.proxySelector());
    }
}
