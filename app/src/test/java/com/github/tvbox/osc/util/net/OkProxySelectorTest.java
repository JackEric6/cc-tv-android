package com.github.tvbox.osc.util.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class OkProxySelectorTest {
    @Test
    public void fallbackTracksSystemProxyChangesAfterSelectorCreation() {
        ProxySelector original = ProxySelector.getDefault();
        OkProxySelector selector = new OkProxySelector();
        URI target = URI.create("https://api.ffzyapi.com/");
        Proxy first = httpProxy(18080);
        Proxy second = httpProxy(18081);
        try {
            ProxySelector.setDefault(new FixedProxySelector(first));
            assertEquals(Collections.singletonList(first), selector.select(target));

            ProxySelector.setDefault(new FixedProxySelector(second));
            assertEquals(Collections.singletonList(second), selector.select(target));
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    private static Proxy httpProxy(int port) {
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", port));
    }

    private static final class FixedProxySelector extends ProxySelector {
        private final Proxy proxy;

        private FixedProxySelector(Proxy proxy) {
            this.proxy = proxy;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
        }
    }
}
