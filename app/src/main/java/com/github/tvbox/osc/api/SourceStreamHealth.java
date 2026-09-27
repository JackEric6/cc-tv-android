package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 播放流健康排序。
 *
 * <p>这里记录的是地址交给播放器后到可播放状态的时间，不读取 CMS 首页接口，也不主动发起探测。
 * 记录只会由真实播放成功/失败回调写入，因此首页不会因为测速而增加网络请求。</p>
 */
public final class SourceStreamHealth {
    public static final long SUCCESS_TTL_MS = 6 * 60 * 60 * 1000L;
    public static final long FAILURE_TTL_MS = 30 * 60 * 1000L;
    public static final long FAILURE_BACKOFF_BASE_MS = 30 * 1000L;
    public static final long FAILURE_BACKOFF_MAX_MS = 6 * 60 * 60 * 1000L;

    private static final List<String> PINNED_KEYS =
            Collections.unmodifiableList(Arrays.asList("xgzy", "360zy", "iqiyi"));
    private static final Map<String, Entry> entries = new HashMap<>();

    private SourceStreamHealth() {
    }

    /** 真实播放器进入可播放状态时调用，elapsedMs 是首个可播放状态的耗时。 */
    public static synchronized void recordSuccess(String sourceKey, long elapsedMs) {
        if (isBlank(sourceKey) || elapsedMs < 0) return;
        Entry entry = entry(sourceKey);
        entry.latencyMs = Math.max(1L, elapsedMs);
        entry.successAtMs = now();
        entry.failureAtMs = 0L;
        entry.failureCount = 0;
        entry.cooldownUntilMs = 0L;
    }

    /** 真实播放器报告错误时调用；失败只影响排序和后续尝试，不阻塞当前播放链路。 */
    public static synchronized void recordFailure(String sourceKey) {
        recordFailure(sourceKey, now());
    }

    static synchronized void recordSuccess(String sourceKey, long elapsedMs, long nowMs) {
        if (isBlank(sourceKey) || elapsedMs < 0) return;
        Entry entry = entry(sourceKey);
        entry.latencyMs = Math.max(1L, elapsedMs);
        entry.successAtMs = nowMs;
        entry.failureAtMs = 0L;
        entry.failureCount = 0;
        entry.cooldownUntilMs = 0L;
    }

    static synchronized void recordFailure(String sourceKey, long nowMs) {
        if (isBlank(sourceKey)) return;
        Entry entry = entry(sourceKey);
        entry.failureAtMs = nowMs;
        entry.failureCount = Math.min(entry.failureCount + 1, 8);
        long backoff = FAILURE_BACKOFF_BASE_MS << Math.max(0, entry.failureCount - 1);
        entry.cooldownUntilMs = nowMs + Math.min(FAILURE_BACKOFF_MAX_MS, backoff);
    }

    /**
     * 返回固定前三后的健康顺序。成功测量按首个可播放耗时升序；未知源维持配置顺序；失败源置于末尾。
     */
    public static synchronized List<SourceBean> order(List<SourceBean> configured) {
        return order(configured, now());
    }

    static synchronized List<SourceBean> order(List<SourceBean> configured, long nowMs) {
        if (configured == null || configured.isEmpty()) return new ArrayList<>();
        List<SourceBean> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String pinned : PINNED_KEYS) {
            for (SourceBean source : configured) {
                if (source != null && pinned.equals(source.getKey()) && added.add(source.getKey())) {
                    result.add(source);
                    break;
                }
            }
        }

        List<RankedSource> measured = new ArrayList<>();
        List<RankedSource> unknown = new ArrayList<>();
        List<RankedSource> failed = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            SourceBean source = configured.get(i);
            if (source == null || added.contains(source.getKey())) continue;
            added.add(source.getKey());
            Entry entry = entries.get(source.getKey());
            if (entry != null && isFailureFresh(entry, nowMs)) {
                failed.add(new RankedSource(source, i));
            } else if (entry != null && isSuccessFresh(entry, nowMs)) {
                measured.add(new RankedSource(source, i));
            } else {
                unknown.add(new RankedSource(source, i));
            }
        }
        Comparator<RankedSource> byLatency = (left, right) -> {
            int cmp = Long.compare(entries.get(left.source.getKey()).latencyMs,
                    entries.get(right.source.getKey()).latencyMs);
            return cmp != 0 ? cmp : Integer.compare(left.configIndex, right.configIndex);
        };
        measured.sort(byLatency);
        for (RankedSource item : measured) result.add(item.source);
        for (RankedSource item : unknown) result.add(item.source);
        for (RankedSource item : failed) result.add(item.source);
        return result;
    }

    /** 仅供单测和配置切换使用；不触发网络请求。 */
    public static synchronized void clear() {
        entries.clear();
    }

    static synchronized long cooldownUntil(String sourceKey) {
        Entry entry = entries.get(sourceKey);
        return entry == null ? 0L : entry.cooldownUntilMs;
    }

    private static boolean isSuccessFresh(Entry entry, long nowMs) {
        return entry.successAtMs > 0 && nowMs >= entry.successAtMs
                && nowMs - entry.successAtMs <= SUCCESS_TTL_MS
                && !(entry.failureAtMs > entry.successAtMs && isFailureFresh(entry, nowMs));
    }

    private static boolean isFailureFresh(Entry entry, long nowMs) {
        return entry.failureAtMs > 0 && nowMs >= entry.failureAtMs
                && nowMs - entry.failureAtMs <= FAILURE_TTL_MS;
    }

    private static Entry entry(String sourceKey) {
        Entry entry = entries.get(sourceKey);
        if (entry == null) {
            entry = new Entry();
            entries.put(sourceKey, entry);
        }
        return entry;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Entry {
        long latencyMs;
        long successAtMs;
        long failureAtMs;
        long cooldownUntilMs;
        int failureCount;
    }

    private static final class RankedSource {
        final SourceBean source;
        final int configIndex;

        RankedSource(SourceBean source, int configIndex) {
            this.source = source;
            this.configIndex = configIndex;
        }
    }
}
