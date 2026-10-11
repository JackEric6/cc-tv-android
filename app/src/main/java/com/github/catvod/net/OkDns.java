package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.OkGoHelper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private static final List<String> FALLBACK_DOH_URLS = Arrays.asList(
            "https://doh.pub/dns-query",
            "https://dns.alidns.com/dns-query",
            "https://doh.360.cn/dns-query");
    private static volatile List<Dns> fallbackResolvers;

    private final ConcurrentHashMap<String, String> hosts = new ConcurrentHashMap<>();

    public void addAll(List<String> hosts) {
        if (hosts == null) return;
        for (String host : hosts) {
            if (host == null) continue;
            String[] splits = host.split("=", 2);
            if (splits.length == 2) this.hosts.put(splits[0].trim(), splits[1].trim());
        }
    }

    public void clear() {
        hosts.clear();
    }

    private String get(String hostname) {
        String target = hosts.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : hosts.entrySet()) {
            if (hostname.contains(entry.getKey())) return entry.getValue();
        }
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Dns dns = OkGoHelper.dnsOverHttps != null ? OkGoHelper.dnsOverHttps : Dns.SYSTEM;
        return lookupWithFallback(get(hostname), dns);
    }

    public static List<InetAddress> lookupWithFallback(String hostname, Dns primary)
            throws UnknownHostException {
        return lookupWithFallback(hostname, primary, Dns.SYSTEM, fallbackResolvers());
    }

    static List<InetAddress> lookupWithFallback(String hostname, Dns primary, Dns system,
                                                 List<Dns> fallbacks)
            throws UnknownHostException {
        UnknownHostException failure = null;
        List<Dns> candidates = new ArrayList<>();
        if (primary != null) candidates.add(primary);
        if (system != null && system != primary) candidates.add(system);
        if (fallbacks != null) {
            for (Dns fallback : fallbacks) {
                if (fallback != null && !candidates.contains(fallback)) candidates.add(fallback);
            }
        }
        for (Dns candidate : candidates) {
            try {
                List<InetAddress> addresses = candidate.lookup(hostname);
                if (addresses != null && !addresses.isEmpty()) return addresses;
                throw new UnknownHostException("DNS returned no addresses for " + hostname);
            } catch (UnknownHostException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
        throw new UnknownHostException("No DNS resolver available for " + hostname);
    }

    private static List<Dns> fallbackResolvers() {
        List<Dns> result = fallbackResolvers;
        if (result != null) return result;
        synchronized (OkDns.class) {
            result = fallbackResolvers;
            if (result != null) return result;
            OkHttpClient client = new OkHttpClient.Builder()
                    .dns(Dns.SYSTEM)
                    .proxySelector(OkGoHelper.proxySelector())
                    .proxyAuthenticator(OkGoHelper.proxyAuthenticator())
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build();
            List<Dns> resolvers = new ArrayList<>();
            for (String url : FALLBACK_DOH_URLS) {
                try {
                    resolvers.add(new DnsOverHttps.Builder()
                            .client(client)
                            .url(HttpUrl.get(url))
                            .build());
                } catch (Throwable ignored) {
                }
            }
            fallbackResolvers = result = Collections.unmodifiableList(resolvers);
            return result;
        }
    }
}
