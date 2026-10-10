package com.github.tvbox.osc.util;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.util.thunder.Jianpian;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 负责把点播媒体缓存为可脱离网络打开的完整媒体文件。
 *
 * <p>明确的媒体直链按原始扩展名直接下载；M3U8、MPD 以及带 type 参数的媒体交给
 * Media3 Transformer 转换为 MP4。解析页、直播流和无法识别的地址不会进入缓存。
 * 所有任务先写入临时文件，成功后再原子改名为最终文件。
 */
public final class OfflineVideoCache {
    private static final String DIRECTORY = "offline-videos";
    private static final String INDEX_FILE = "offline-tasks.json";
    // iOS URLSession 会把网络回调与磁盘写入分离；安卓这里用更大的顺序缓冲，
    // 降低高码率直链上 read/锁/进度回调的固定开销。
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int DIRECT_PARALLEL_WORKERS = 6;
    private static final long DIRECT_PARALLEL_MIN_BYTES = 8L * 1024L * 1024L;
    private static final long DIRECT_RANGE_CHUNK_BYTES = 8L * 1024L * 1024L;
    // 部分资源站/CDN 对同一文件的并发 Range 请求会限流或长期不返回数据，
    // 导致任务停在固定百分比。单连接 Range 虽然峰值速度较低，但暂停/续传更可靠。
    private static final boolean ENABLE_DIRECT_PARALLEL = false;
    // 进度值在内存中持续更新；索引落盘与 UI 通知按 iOS 同类实现降频，
    // 避免多路下载时频繁 I/O 抢占网络线程，同时保持页面约每秒更新一次。
    private static final long PROGRESS_FLUSH_INTERVAL_MS = 1_000L;
    private static final long PROGRESS_FLUSH_BYTES = 4L * 1024L * 1024L;
    private static final long SPEED_WINDOW_NS = 1_000_000_000L;
    // iOS 下载器在约 50ms 后就开始给出速度；Android 也不能等满 1 秒才显示，
    // 否则短分片和高速直链在界面上长期显示为 0 B/s。
    private static final long SPEED_MIN_SAMPLE_NS = 50_000_000L;
    private static final int MAX_SPEED_SAMPLES = 24;
    private static final int DIRECT_DOWNLOAD_ATTEMPTS = 4;
    private static final long DIRECT_RETRY_BASE_MS = 500L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MIN_DURATION_ESTIMATE_SAMPLE_MS = 15_000L;
    /** Shared cap for independent offline downloads across direct, HLS, and transformed media. */
    static final int MAX_ACTIVE_DOWNLOADS = 5;
    private static final Semaphore DOWNLOAD_SLOTS =
            new Semaphore(MAX_ACTIVE_DOWNLOADS, true);
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_ACTIVE_DOWNLOADS, r -> {
        Thread thread = new Thread(r, "avbox-offline-video");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<String, DownloadHandle> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DownloadHandle> ALL_ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ProgressState> PROGRESS_STATES = new ConcurrentHashMap<>();
    private static final Map<String, TaskRecord> TASKS = new LinkedHashMap<>();
    private static final Set<TaskListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Object TASK_LOCK = new Object();
    private static volatile File taskIndexFile;
    private static volatile boolean tasksLoaded;
    private static volatile String loadedFilesDirectory;
    private static volatile OkHttpClient downloadClient;

    /**
     * 下载专用连接池，保留应用当前的 DNS、代理和请求头拦截器，但不继承点播/图片请求
     * 的低并发调度限制。HLS 一个任务会并发请求多个分片，专用 Dispatcher 能避免被同一
     * 主机的普通请求队列拖慢；连接池长期复用也减少每个分片的握手开销。
     */
    static OkHttpClient downloadClient() {
        OkHttpClient current = downloadClient;
        if (current != null) return current;
        synchronized (OfflineVideoCache.class) {
            current = downloadClient;
            if (current == null) {
                Dispatcher dispatcher = new Dispatcher();
                // 批量缓存时每集会同时拉取多个 HLS 分片；这里不能让 OkHttp
                // 的默认主机队列把多个瓜子任务压回到单连接吞吐。
                dispatcher.setMaxRequests(256);
                dispatcher.setMaxRequestsPerHost(256);
                current = OkHttp.client().newBuilder()
                        .dispatcher(dispatcher)
                        .connectionPool(new ConnectionPool(128, 5, java.util.concurrent.TimeUnit.MINUTES))
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(45, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        .build();
                downloadClient = current;
            }
            return current;
        }
    }

    static boolean acquireDownloadSlot(DownloadHandle handle) {
        if (handle == null || handle.isCancelled()) return false;
        try {
            while (!handle.isCancelled()) {
                if (DOWNLOAD_SLOTS.tryAcquire(250L, TimeUnit.MILLISECONDS)) {
                    if (handle.isCancelled()) {
                        DOWNLOAD_SLOTS.release();
                        return false;
                    }
                    return true;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    static void releaseDownloadSlot() {
        DOWNLOAD_SLOTS.release();
    }

    private OfflineVideoCache() {
    }

    /** A task is not stopped until cancelled child requests have closed their file streams. */
    static void shutdownAndAwait(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** 仅接受 http(s) 且 URL path 明确以可播放媒体扩展名结尾的直链。 */
    public static boolean isExplicitMp4DirectUrl(String url) {
        return ".mp4".equals(explicitMediaExtension(url));
    }

    /** 取得明确媒体直链的原始扩展名；播放列表和解析页返回空字符串。 */
    static String explicitMediaExtension(String url) {
        if (url == null || url.isEmpty()) return "";
        String value = url.trim();
        if (!(value.startsWith("http://") || value.startsWith("https://"))) return "";
        // 荐片的 P2P 桥接虽然使用 .m3u8 路径，但响应会 302 到 P2P 引擎提供的
        // MP4 字节流。它不是 HLS，必须进入普通 Range 下载器，否则会被 HLS
        // 解析器当成播放列表，暂停后也就无法复用 .part 续传文件。
        if (Jianpian.isDownloadBridgeUrl(value)) return ".mp4";
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(value);
            String path = parsed.encodedPath().toLowerCase(Locale.ROOT);
            String[] extensions = {".mp4", ".m4v", ".ts", ".flv", ".mkv", ".webm", ".mov",
                    ".avi", ".wmv", ".mpg", ".mpeg", ".3gp", ".ogv"};
            for (String extension : extensions) {
                if (path.endsWith(extension)) return extension;
            }
            for (String parameter : new String[]{"type", "format", "mime", "content-type"}) {
                String mediaType = parsed.queryParameter(parameter);
                if (mediaType == null || mediaType.isEmpty()) continue;
                String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("video/")) normalized = normalized.substring("video/".length());
                for (String extension : extensions) {
                    String extensionValue = extension.substring(1);
                    if (normalized.equals(extensionValue)
                            || normalized.endsWith("/" + extensionValue)) return extension;
                }
                if (normalized.equals("x-matroska")) return ".mkv";
                if (normalized.equals("mpeg2-ts") || normalized.equals("mp2t")) return ".ts";
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isCacheableVodUrl(String url) {
        if (Jianpian.isDownloadBridgeUrl(url)) return true;
        String extension = explicitMediaExtension(url);
        return extension != null && !extension.isEmpty()
                || OfflineHlsTransformer.isHlsVodCandidate(url)
                || OfflineMediaTransformer.isConvertibleMediaUrl(url);
    }

    /**
     * 逻辑任务键只描述同一部影片的同一集，不把易变的签名播放地址放进唯一键。
     * 资源站更换 CDN、刷新 token 或切换播放线路时，仍应复用同一个下载任务。
     * 这与 iOS 下载管理器的 source + video + episode 业务键保持一致。
     */
    public static String buildCacheKey(String sourceKey, String movieId, String playFlag,
                                       int episodeIndex, String remoteUrl) {
        String source = safe(sourceKey);
        String movie = safe(movieId);
        int episode = Math.max(0, episodeIndex);
        if (!TextUtils.isEmpty(movie)) {
            return source + "|" + movie + "|E" + episode;
        }
        // 缺少影片 ID 时无法建立稳定业务键，退回 URL 摘要避免不同结果互相覆盖。
        return source + "|" + safe(playFlag) + "|E" + episode + "|"
                + MD5.string2MD5(normalizeRemoteUrl(remoteUrl));
    }

    /** 旧版本把完整播放 URL 放进任务键，保留它只用于迁移旧缓存文件。 */
    private static String buildLegacyCacheKey(String sourceKey, String movieId, String playFlag,
                                              int episodeIndex, String remoteUrl) {
        return safe(sourceKey) + "|" + safe(movieId) + "|" + safe(playFlag) + "|"
                + episodeIndex + "|" + MD5.string2MD5(normalizeRemoteUrl(remoteUrl));
    }

    static String normalizeRemoteUrl(String remoteUrl) {
        return remoteUrl == null ? "" : remoteUrl.trim();
    }

    @Nullable
    public static File getCachedFile(Context context, String sourceKey, String movieId,
                                     String playFlag, int episodeIndex, String remoteUrl) {
        if (context == null || !isCacheableVodUrl(remoteUrl)) return null;
        ensureTasksLoaded(context);
        String key = buildCacheKey(sourceKey, movieId, playFlag, episodeIndex, remoteUrl);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(key);
            if (task != null && task.status != TaskStatus.COMPLETED) return null;
        }
        File taskTarget = taskTargetFile(key);
        if (isFinalCacheFile(taskTarget)) return taskTarget;
        File target = findExistingTarget(context, key, remoteUrl);
        if (isFinalCacheFile(target)) return target;
        // 兼容改版前已经完成的缓存（旧版本任务键包含完整 URL）。
        File legacy = findExistingTarget(context,
                buildLegacyCacheKey(sourceKey, movieId, playFlag, episodeIndex, remoteUrl), remoteUrl);
        return isFinalCacheFile(legacy) ? legacy : null;
    }

    /** 未命中时后台下载；同一个缓存键不会重复下载。播放本身继续使用远程地址。 */
    public static DownloadHandle prefetch(Context context, String sourceKey, String movieId,
                                          String playFlag, int episodeIndex, String remoteUrl,
                                          @Nullable Map<String, String> headers) {
        final String normalizedRemoteUrl = normalizeRemoteUrl(remoteUrl);
        if (context == null || !isCacheableVodUrl(normalizedRemoteUrl)) return DownloadHandle.noop();
        ensureTasksLoaded(context);
        final String cacheKey = buildCacheKey(sourceKey, movieId, playFlag, episodeIndex,
                normalizedRemoteUrl);
        final boolean pausedTask = isPausedTask(context, cacheKey);
        File cached = getCachedFile(context, sourceKey, movieId, playFlag, episodeIndex,
                normalizedRemoteUrl);
        if (!pausedTask && isCompletedTask(context, cacheKey)
                && isFinalCacheFile(cached)) {
            String cachedKey = buildCacheKey(sourceKey, movieId, playFlag, episodeIndex,
                    normalizedRemoteUrl);
            registerTask(context, cachedKey, sourceKey, movieId, playFlag, episodeIndex,
                    normalizedRemoteUrl, headers, cached);
            markCompleted(context, cachedKey, cached);
            return DownloadHandle.completed(cached);
        }
        // 荐片 P2P 桥接虽然使用 .m3u8 路径，响应实际是 P2P 引擎提供的 MP4
        // 字节流，不能进入 HLS 转换器；否则会等待播放列表超时，下载句柄也
        // 不会进入普通暂停/Range 续传流程。
        if (shouldUseHlsPrefetch(normalizedRemoteUrl)) {
            return OfflineHlsTransformer.prefetch(context, sourceKey, movieId, playFlag,
                    episodeIndex, normalizedRemoteUrl, headers);
        }
        String extension = explicitMediaExtension(normalizedRemoteUrl);
        if (extension == null || extension.isEmpty()) {
            return OfflineMediaTransformer.prefetch(context, sourceKey, movieId, playFlag,
                    episodeIndex, normalizedRemoteUrl, headers);
        }
        final String key = cacheKey;
        File target = targetFile(context, key, normalizedRemoteUrl);
        if (!pausedTask && isCompletedTask(context, key)
                && isFinalCacheFile(target)) {
            registerTask(context, key, sourceKey, movieId, playFlag, episodeIndex,
                    normalizedRemoteUrl, headers, target);
            markCompleted(context, key, target);
            return DownloadHandle.completed(target);
        }
        DownloadHandle existing = ACTIVE.get(key);
        if (existing != null) {
            if (!existing.isCancelled()) return existing;
            synchronized (TASK_LOCK) {
                ACTIVE.remove(key, existing);
                ALL_ACTIVE.remove(key, existing);
            }
        }
        DownloadHandle handle = new DownloadHandle();
        final File part = new File(target.getParentFile(), target.getName() + ".part");
        registerTask(context, key, sourceKey, movieId, playFlag, episodeIndex,
                normalizedRemoteUrl, headers, target);
        ALL_ACTIVE.put(key, handle);
        DownloadHandle raced = ACTIVE.putIfAbsent(key, handle);
        if (raced != null) {
            ALL_ACTIVE.remove(key, handle);
            return raced;
        }
        Map<String, String> safeHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        handle.setCancelAction(() -> {
            if (!handle.isPauseRequested()) {
                if (part.exists()) part.delete();
                ACTIVE.remove(key, handle);
                ALL_ACTIVE.remove(key, handle);
            }
        });
        handle.setFuture(DOWNLOAD_EXECUTOR.submit(() -> {
            if (!handle.markStarted()) {
                handle.markStopped();
                return;
            }
            boolean slotAcquired = acquireDownloadSlot(handle);
            if (!slotAcquired) {
                ACTIVE.remove(key, handle);
                ALL_ACTIVE.remove(key, handle);
                handle.markStopped();
                return;
            }
            try {
                if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) return;
                long offset = part.isFile() ? Math.max(0, part.length()) : 0;
                long expectedTotal = -1;
                long preflightTotal = -1;
                long rangeProbeTotal = -1;
                boolean preflightAttempted = false;
                boolean copied = false;
                boolean downloadStarted = false;
                String lastDownloadError = "下载未完成";
                // 临时网络错误与服务端短响应有限重试；有 Range 时续传，Range 被忽略时才清理重下。
                for (int attempt = 0; attempt < DIRECT_DOWNLOAD_ATTEMPTS && !handle.cancelled; attempt++) {
                    offset = part.isFile() ? Math.max(0L, part.length()) : 0L;
                    // 对支持 Range 的大文件先使用分段并发下载；不支持 Range 或无法探测
                    // 总长度时返回 false，继续走下面的 iOS 风格单任务断点续传。
                    if (offset == 0L && ENABLE_DIRECT_PARALLEL) {
                        if (!preflightAttempted) {
                            rangeProbeTotal = probeRangeLength(normalizedRemoteUrl, safeHeaders, handle);
                            preflightTotal = rangeProbeTotal > 0L
                                    ? rangeProbeTotal
                                    : probeHeadLength(normalizedRemoteUrl, safeHeaders, handle);
                            preflightAttempted = true;
                        }
                        ParallelDownloadResult parallel = tryParallelDirectDownload(
                                context, key, normalizedRemoteUrl, safeHeaders, target,
                                part, handle, rangeProbeTotal);
                        if (parallel == ParallelDownloadResult.COMPLETED) return;
                        if (parallel == ParallelDownloadResult.CANCELLED) return;
                        if (parallel == ParallelDownloadResult.FAILED) {
                            if (!handle.cancelled) markFailed(context, key, "并行下载失败", handle);
                            return;
                        }
                    }
                    Request.Builder builder = new Request.Builder().url(normalizedRemoteUrl);
                    Headers.Builder headerBuilder = new Headers.Builder();
                    for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
                        if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                            try { headerBuilder.add(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
                        }
                    }
                    headerBuilder.set("Accept-Encoding", "identity");
                    boolean wantsRange = offset > 0;
                    if (wantsRange) headerBuilder.set("Range", "bytes=" + offset + "-");
                    builder.headers(headerBuilder.build());
                    Call call = downloadClient().newCall(builder.build());
                    handle.call = call;
                    handle.registerCall(call);
                    try (Response response = call.execute()) {
                        if (handle.cancelled) return;
                        if (wantsRange && response.code() == 416) {
                            // 断点刚好已经落到文件末尾时，部分 CDN 会返回 416。
                            // 只在 Content-Range 明确给出总长度且本地 .part 完全匹配时
                            // 允许直接提交，避免把已完成的断点误判为失败或重新下载。
                            long rangeTotal = contentRangeTotal(response);
                            if (rangeTotal > 0 && part.isFile() && part.length() == rangeTotal) {
                                if (!commitPart(part, target, key, handle)) {
                                    markFailed(context, key, "保存缓存文件失败", handle);
                                } else {
                                    handle.completedFile = target;
                                    markCompleted(context, key, target, handle);
                                }
                                return;
                            }
                            if (part.exists() && !part.delete()) {
                                markFailed(context, key, "无法重置临时缓存", handle);
                                return;
                            }
                            if (attempt + 1 < DIRECT_DOWNLOAD_ATTEMPTS) continue;
                            lastDownloadError = "服务器拒绝断点续传";
                            break;
                        }
                        if (!response.isSuccessful() || response.body() == null) {
                            lastDownloadError = "网络响应无效 (HTTP " + response.code() + ")";
                            if (isRetryableHttpStatus(response.code())
                                    && attempt + 1 < DIRECT_DOWNLOAD_ATTEMPTS) {
                                response.close();
                                if (!waitBeforeDirectRetry(handle, attempt)) return;
                                continue;
                            }
                            markFailed(context, key, lastDownloadError, handle);
                            return;
                        }
                        boolean append = wantsRange && acceptsRange(response, offset);
                        if (wantsRange && !append) {
                            // 200 表示服务端忽略 Range；206 但起始位置不一致也不能追加。
                            if (part.exists() && !part.delete()) {
                                markFailed(context, key, "无法重置临时缓存", handle);
                                return;
                            }
                            if (attempt + 1 < DIRECT_DOWNLOAD_ATTEMPTS) continue;
                            lastDownloadError = "服务器不支持有效的断点续传";
                            break;
                        }
                        String contentType = response.header("Content-Type", "");
                        if (isRejectedContentType(contentType)) {
                            markFailed(context, key, "服务器返回了不可缓存的内容", handle);
                            return;
                        }
                        long bodyLength = response.body().contentLength();
                        long responseRangeTotal = append ? contentRangeTotal(response) : -1;
                        long responseTotal = responseRangeTotal > 0
                                ? responseRangeTotal
                                : (bodyLength > 0 ? (append ? offset + bodyLength : bodyLength)
                                : preflightTotal);
                        if (responseTotal > 0) expectedTotal = responseTotal;
                        if (isJianpianDownloadUrl(normalizedRemoteUrl)) {
                            // P2P 原生缓存可能已经先于本地 HTTP 复制器写入 .ybbk；
                            // 不能用普通新任务路径把这部分真实进度清零。
                            markDownloadingForResume(context, key, expectedTotal, handle);
                        } else if (downloadStarted) {
                            markDownloadingForResume(context, key, expectedTotal, handle);
                        } else {
                            markDownloading(context, key, expectedTotal, handle);
                        }
                        downloadStarted = true;
                        if (append && offset > 0) {
                            markProgress(context, key, offset, expectedTotal, true, handle);
                        }
                        copied = copyStream(response.body().byteStream(), part, handle, context, key,
                                expectedTotal, append, append ? offset : 0);
                        if (!copied) {
                            if (!handle.cancelled) markFailed(context, key, "下载未完成", handle);
                            return;
                        }
                        offset = part.isFile() ? part.length() : 0;
                        if (offset > MAX_FILE_BYTES) {
                            markFailed(context, key, "缓存文件超过大小限制", handle);
                            return;
                        }
                        if (expectedTotal > 0 && offset < expectedTotal) {
                            copied = false;
                            lastDownloadError = "服务器提前结束了响应，正在续传";
                            if (attempt + 1 < DIRECT_DOWNLOAD_ATTEMPTS) {
                                response.close();
                                if (!waitBeforeDirectRetry(handle, attempt)) return;
                                continue;
                            }
                            break;
                        }
                        if (expectedTotal > 0 && offset > expectedTotal) {
                            markFailed(context, key, "下载文件长度超过服务器声明大小", handle);
                            return;
                        }
                        copied = true;
                        break;
                    } catch (IOException networkError) {
                        lastDownloadError = TextUtils.isEmpty(networkError.getMessage())
                                ? "网络连接中断" : networkError.getMessage();
                        copied = false;
                        if (attempt + 1 >= DIRECT_DOWNLOAD_ATTEMPTS) {
                            markFailed(context, key, lastDownloadError, handle);
                            return;
                        }
                        if (!waitBeforeDirectRetry(handle, attempt)) return;
                    } finally {
                        handle.unregisterCall(call);
                        handle.call = null;
                    }
                }
                if (!copied) {
                    if (!handle.cancelled) markFailed(context, key, lastDownloadError, handle);
                    return;
                }
                if (handle.cancelled) {
                    return;
                }
                if (!part.isFile() || part.length() <= 0 || part.length() > MAX_FILE_BYTES) {
                    markFailed(context, key, "缓存文件为空或超过大小限制", handle);
                    return;
                }
                // 已知长度时，短文件只能保留为 .part，等待下一次继续下载。
                if (expectedTotal > 0 && part.length() != expectedTotal) {
                    markFailed(context, key, "下载文件长度不完整", handle);
                    return;
                }
                if (!commitPart(part, target, key, handle)) {
                    markFailed(context, key, "保存缓存文件失败", handle);
                    return;
                }
                handle.completedFile = target;
                markCompleted(context, key, target, handle);
            } catch (Throwable th) {
                LOG.i("offline-video-cache-failed key=" + key + " error=" + th.getMessage());
                if (!handle.cancelled) markFailed(context, key,
                        TextUtils.isEmpty(th.getMessage()) ? "下载失败" : th.getMessage(), handle);
            } finally {
                ACTIVE.remove(key, handle);
                ALL_ACTIVE.remove(key, handle);
                releaseDownloadSlot();
                handle.markStopped();
            }
        }));
        return handle;
    }

    /** 荐片 P2P 桥接的 .m3u8 路径是 MP4 重定向，不属于 HLS 播放列表。 */
    static boolean shouldUseHlsPrefetch(String url) {
        return !Jianpian.isDownloadBridgeUrl(url) && OfflineHlsTransformer.isHlsVodCandidate(url);
    }

    static boolean copyStream(InputStream input, File part, DownloadHandle handle) throws IOException {
        return copyStream(input, part, handle, null, null, -1, false, 0);
    }

    private static boolean copyStream(InputStream input, File part, DownloadHandle handle,
                                     Context context, String key, long expectedBytes,
                                     boolean append, long initialBytes) throws IOException {
        long total = Math.max(0, initialBytes);
        try (InputStream in = new BufferedInputStream(input);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part, append))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            boolean firstChunk = true;
            while ((read = in.read(buffer)) != -1) {
                if (handle != null && handle.cancelled) return false;
                if (firstChunk) {
                    firstChunk = false;
                    if (looksLikePlaylistOrHtml(buffer, read)) return false;
                }
                total += read;
                if (total > MAX_FILE_BYTES) return false;
                out.write(buffer, 0, read);
                if (context != null && key != null) markProgress(context, key, total, expectedBytes,
                        true, handle);
            }
            out.flush();
        }
        return total > 0 && (handle == null || !handle.cancelled);
    }

    private enum ParallelDownloadResult { COMPLETED, FALLBACK, FAILED, CANCELLED }

    /**
     * 对明确的媒体直链做可回退的 Range 分段并发下载。
     *
     * <p>很多资源站 CDN 支持 Range，但单连接会被限速到 1~2 MB/s；多个独立 Range
     * 能让同一条链路接近 iOS URLSession 的实际吞吐。探测失败、服务端忽略 Range、
     * 或已有旧版 .part 时回退到原有单连接断点续传，不改变兼容性。</p>
     */
    private static ParallelDownloadResult tryParallelDirectDownload(
            Context context, String key, String url, Map<String, String> headers,
            File target, File part, DownloadHandle handle, long totalBytes) {
        if (handle == null || handle.cancelled || part.isFile() || target == null) {
            return handle != null && handle.cancelled
                    ? ParallelDownloadResult.CANCELLED : ParallelDownloadResult.FALLBACK;
        }
        if (totalBytes < DIRECT_PARALLEL_MIN_BYTES || totalBytes > MAX_FILE_BYTES) {
            return handle.cancelled ? ParallelDownloadResult.CANCELLED : ParallelDownloadResult.FALLBACK;
        }
        int workers = (int) Math.min(DIRECT_PARALLEL_WORKERS,
                Math.max(2L, (totalBytes + DIRECT_RANGE_CHUNK_BYTES - 1L)
                        / DIRECT_RANGE_CHUNK_BYTES));
        long chunkSize = (totalBytes + workers - 1L) / workers;
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) return ParallelDownloadResult.FAILED;
        List<File> completeParts = new ArrayList<>();
        List<File> temporaryParts = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            File complete = new File(parent, part.getName() + ".range-" + i);
            completeParts.add(complete);
            temporaryParts.add(new File(parent, complete.getName() + ".part"));
        }
        final ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "avbox-offline-direct-range");
            thread.setDaemon(true);
            return thread;
        });
        final List<Future<Boolean>> futures = new CopyOnWriteArrayList<>();
        final CompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);
        final AtomicLong downloaded = new AtomicLong(0L);
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
        for (int i = 0; i < completeParts.size(); i++) {
            long start = i * chunkSize;
            long end = Math.min(totalBytes - 1L, start + chunkSize - 1L);
            long expected = end - start + 1L;
            File complete = completeParts.get(i);
            boolean completePartUsable = complete.isFile() && complete.length() == expected;
            if (completePartUsable) {
                downloaded.addAndGet(expected);
            } else if (complete.exists()) {
                // 被暂停/进程中断的分片可能只写入了一部分，不能把它当作完整分片。
                // 后续任务会从对应的 .part 偏移继续，错误的完成文件必须先移除。
                if (!complete.delete()) return ParallelDownloadResult.FAILED;
            }
            File temporary = temporaryParts.get(i);
            if (!completePartUsable && temporary.isFile()) {
                long partial = Math.max(0L, temporary.length());
                if (partial > expected) {
                    if (!temporary.delete()) return ParallelDownloadResult.FAILED;
                    partial = 0L;
                }
                downloaded.addAndGet(partial);
            }
        }
        markDownloading(context, key, totalBytes, handle);
        if (downloaded.get() > 0) markProgress(context, key, downloaded.get(), totalBytes, true, handle);
        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                final long start = index * chunkSize;
                final long end = Math.min(totalBytes - 1L, start + chunkSize - 1L);
                final File complete = completeParts.get(index);
                final File temporary = temporaryParts.get(index);
                if (complete.isFile() && complete.length() == end - start + 1L) {
                    futures.add(completionService.submit(() -> true));
                    continue;
                }
                if (complete.exists()) complete.delete();
                futures.add(completionService.submit(() -> downloadRangePart(
                        context, key, url, headers, start, end, complete, temporary,
                        totalBytes, downloaded, handle, calls)));
            }
            for (int completed = 0; completed < futures.size(); completed++) {
                if (handle.cancelled) return handle.isPauseRequested()
                        ? ParallelDownloadResult.CANCELLED : ParallelDownloadResult.CANCELLED;
                if (!completionService.take().get()) return handle.cancelled
                        ? ParallelDownloadResult.CANCELLED : ParallelDownloadResult.FALLBACK;
            }
            if (handle.cancelled) return ParallelDownloadResult.CANCELLED;
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(part))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                for (File complete : completeParts) {
                    if (handle.cancelled) return ParallelDownloadResult.CANCELLED;
                    try (InputStream input = new BufferedInputStream(new FileInputStream(complete))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                }
                output.flush();
            }
            if (!part.isFile() || part.length() != totalBytes) {
                if (part.exists() && !handle.isPauseRequested()) part.delete();
                return ParallelDownloadResult.FALLBACK;
            }
            if (!commitPart(part, target, key, handle)) return ParallelDownloadResult.FAILED;
            handle.completedFile = target;
            markCompleted(context, key, target, handle);
            return ParallelDownloadResult.COMPLETED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ParallelDownloadResult.CANCELLED;
        } catch (Throwable error) {
            LOG.i("offline-direct-range-failed key=" + key + " error=" + error.getMessage());
            return handle.cancelled ? ParallelDownloadResult.CANCELLED : ParallelDownloadResult.FALLBACK;
        } finally {
            for (Call call : calls) {
                try { call.cancel(); } catch (Throwable ignored) { }
            }
            for (Future<Boolean> future : futures) future.cancel(true);
            shutdownAndAwait(executor);
            if (target.isFile() && target.length() > 0) {
                for (File file : completeParts) if (file.exists()) file.delete();
                for (File file : temporaryParts) if (file.exists()) file.delete();
            } else if (!handle.isPauseRequested()) {
                // 只有暂停才保留完整 range 文件；失败/取消/回退时清掉残留，避免
                // 下一次单连接下载旁边积累无主分段。
                if (part.exists()) part.delete();
                for (File file : completeParts) if (file.exists()) file.delete();
                for (File file : temporaryParts) if (file.exists()) file.delete();
            } else {
                // 暂停时保留未完成的 Range 临时片段；downloadRangePart 会在下一轮
                // 从 temporary.length() 继续请求。显式取消/失败走上面的清理分支。
            }
        }
    }

    private static long probeRangeLength(String url, Map<String, String> headers,
                                         DownloadHandle handle) {
        Call call = null;
        try {
            Request.Builder builder = new Request.Builder().url(url);
            Headers.Builder headerBuilder = new Headers.Builder();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                        try { headerBuilder.add(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
                    }
                }
            }
            headerBuilder.set("Accept-Encoding", "identity");
            headerBuilder.set("Range", "bytes=0-0");
            builder.headers(headerBuilder.build());
            call = downloadClient().newCall(builder.build());
            handle.registerCall(call);
            try (Response response = call.execute()) {
                if (response.code() != 206 || response.body() == null) return -1L;
                String range = response.header("Content-Range", "");
                int slash = range.lastIndexOf('/');
                if (slash < 0) return -1L;
                long total = Long.parseLong(range.substring(slash + 1).trim());
                return total > 0 ? total : -1L;
            }
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (call != null) handle.unregisterCall(call);
        }
    }

    private static long probeHeadLength(String url, Map<String, String> headers,
                                        DownloadHandle handle) {
        Call call = null;
        try {
            Request.Builder builder = new Request.Builder().url(url).head();
            Headers.Builder headerBuilder = new Headers.Builder();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                        try { headerBuilder.add(entry.getKey(), entry.getValue()); }
                        catch (Throwable ignored) { }
                    }
                }
            }
            headerBuilder.set("Accept-Encoding", "identity");
            builder.headers(headerBuilder.build());
            call = downloadClient().newCall(builder.build());
            handle.registerCall(call);
            try (Response response = call.execute()) {
                return parsePositiveContentLength(response.header("Content-Length", ""));
            }
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (call != null) handle.unregisterCall(call);
        }
    }

    static long parsePositiveContentLength(String value) {
        if (value == null || value.trim().isEmpty()) return -1L;
        try {
            long length = Long.parseLong(value.trim());
            return length > 0L ? length : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean matchesRange(Response response, long expectedStart, long expectedEnd) {
        if (response == null) return false;
        String value = response.header("Content-Range", "").trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes ")) return false;
        int dash = value.indexOf('-', 6);
        int slash = value.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) return false;
        try {
            long start = Long.parseLong(value.substring(6, dash).trim());
            long end = Long.parseLong(value.substring(dash + 1, slash).trim());
            return start == expectedStart && end == expectedEnd;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean downloadRangePart(Context context, String key, String url,
                                              Map<String, String> headers, long start, long end,
                                              File complete, File temporary, long totalBytes,
                                              AtomicLong downloaded, DownloadHandle handle,
                                              List<Call> calls) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (handle.cancelled) return false;
            Call call = null;
            try {
                long expected = end - start + 1L;
                long localOffset = temporary.isFile() ? temporary.length() : 0L;
                if (localOffset >= expected) {
                    if (localOffset == expected && temporary.renameTo(complete)) return true;
                    if (temporary.exists()) temporary.delete();
                    // 初始化进度时已把临时片段按 expected 截断计入，重置时要撤销，
                    // 否则会把废弃字节与新下载字节叠加，进度可能跳到 99%。
                    if (localOffset > 0L) downloaded.addAndGet(-Math.min(localOffset, expected));
                    localOffset = 0L;
                }
                Request.Builder builder = new Request.Builder().url(url);
                Headers.Builder headerBuilder = new Headers.Builder();
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                            try { headerBuilder.add(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
                        }
                    }
                }
                headerBuilder.set("Accept-Encoding", "identity");
                headerBuilder.set("Range", "bytes=" + (start + localOffset) + "-" + end);
                builder.headers(headerBuilder.build());
                call = downloadClient().newCall(builder.build());
                calls.add(call);
                handle.registerCall(call);
                try (Response response = call.execute()) {
                    if (response.code() != 206 || response.body() == null) throw new IOException("Range 不受支持");
                    if (!matchesRange(response, start + localOffset, end)) {
                        throw new IOException("Range 起点异常");
                    }
                    long responseExpected = expected - localOffset;
                    long contentLength = response.body().contentLength();
                    if (contentLength > 0 && contentLength != responseExpected) throw new IOException("Range 长度异常");
                    long local = localOffset;
                    try (InputStream input = new BufferedInputStream(response.body().byteStream());
                         OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary, localOffset > 0L))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        boolean first = true;
                        while ((read = input.read(buffer)) != -1) {
                            if (handle.cancelled) return false;
                            if (first) {
                                first = false;
                                if (looksLikePlaylistOrHtml(buffer, read)) throw new IOException("服务器返回了错误页面");
                            }
                            local += read;
                            if (local > expected || downloaded.get() + read > MAX_FILE_BYTES) {
                                throw new IOException("缓存文件超过大小限制");
                            }
                            output.write(buffer, 0, read);
                            downloaded.addAndGet(read);
                            markProgress(context, key, downloaded.get(), totalBytes, true, handle);
                        }
                        output.flush();
                    }
                    if (local != expected) throw new IOException("Range 数据不完整");
                    if (!temporary.renameTo(complete)) throw new IOException("保存 Range 分片失败");
                    return true;
                }
            } catch (Throwable error) {
                if (attempt == 2 || handle.cancelled) return false;
                try { Thread.sleep(200L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            } finally {
                if (call != null) {
                    calls.remove(call);
                    handle.unregisterCall(call);
                }
            }
        }
        return false;
    }

    /** 只有明确的 206 且 Content-Range 起点等于本地临时文件长度时才允许追加。 */
    static boolean acceptsRange(Response response, long offset) {
        if (response == null || response.code() != 206 || offset <= 0) return false;
        String contentRange = response.header("Content-Range", "");
        if (TextUtils.isEmpty(contentRange)) return false;
        try {
            String value = contentRange.trim().toLowerCase(Locale.ROOT);
            if (!value.startsWith("bytes ")) return false;
            int dash = value.indexOf('-', 6);
            if (dash < 0) return false;
            long start = Long.parseLong(value.substring(6, dash).trim());
            return start == offset;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从 206/416 的 Content-Range 中提取完整实体长度。 */
    static long contentRangeTotal(Response response) {
        if (response == null) return -1L;
        String value = response.header("Content-Range", "").trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes ")) return -1L;
        int slash = value.indexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return -1L;
        try {
            long total = Long.parseLong(value.substring(slash + 1).trim());
            return total > 0 ? total : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean looksLikePlaylistOrHtml(byte[] data, int length) {
        if (data == null || length <= 0) return true;
        int probeLength = Math.min(length, 4096);
        String probe = new String(data, 0, probeLength, StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        return probe.startsWith("#extm3u") || probe.startsWith("<html")
                || probe.startsWith("<!doctype") || probe.startsWith("{\"playlist\"")
                || probe.startsWith("{\"error\"");
    }

    static boolean commitPart(File part, File target) {
        if (part == null || target == null || !part.isFile() || part.length() <= 0) return false;
        if (target.exists() && !target.delete()) return false;
        // part 与 target 始终位于同一目录，renameTo 在此处是原子提交边界。
        return part.renameTo(target);
    }

    /** 带句柄校验的原子提交；旧一轮任务不能把最终文件提交给新一轮任务。 */
    static boolean commitPart(File part, File target, String id,
                              @Nullable DownloadHandle handle) {
        if (TextUtils.isEmpty(id) || !isCurrentHandle(id, handle)) return false;
        synchronized (TASK_LOCK) {
            if (!isCurrentHandle(id, handle)) return false;
            return commitPart(part, target);
        }
    }

    static File cachedFileForDirectory(File directory, String sourceKey, String movieId,
                                       String playFlag, int episodeIndex, String remoteUrl) {
        if (directory == null || !isCacheableVodUrl(remoteUrl)) return null;
        return new File(directory, MD5.string2MD5(buildCacheKey(sourceKey, movieId, playFlag,
                episodeIndex, remoteUrl)) + cacheExtension(remoteUrl));
    }

    static boolean isUsableCacheFile(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    /** 只有原子提交后的最终文件才可以作为可播放缓存。 */
    static boolean isFinalCacheFile(File file) {
        if (!isUsableCacheFile(file)) return false;
        String name = file.getName();
        return !name.endsWith(".part") && !name.contains(".seg-");
    }

    private static File taskTargetFile(String id) {
        if (TextUtils.isEmpty(id)) return null;
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || task.status != TaskStatus.COMPLETED
                    || TextUtils.isEmpty(task.targetPath)) return null;
            File target = new File(task.targetPath);
            return isFinalCacheFile(target) ? target : null;
        }
    }

    /** 按稳定任务键查找所有扩展名，允许播放地址从 m3u8/ts 切换为 mp4 后继续命中。 */
    private static File findExistingTarget(Context context, String key, String remoteUrl) {
        if (context == null || TextUtils.isEmpty(key)) return null;
        File preferred = targetFile(context, key, remoteUrl);
        if (isFinalCacheFile(preferred)) return preferred;
        File ts = targetFileForExtension(context, key, ".ts");
        if (isFinalCacheFile(ts)) return ts;
        File mp4 = targetFileForExtension(context, key, ".mp4");
        if (isFinalCacheFile(mp4)) return mp4;
        File directory = new File(context.getFilesDir(), DIRECTORY);
        String prefix = MD5.string2MD5(key);
        File[] candidates = directory.listFiles((dir, name) -> name.startsWith(prefix)
                && !name.endsWith(".part") && !name.contains(".seg-"));
        if (candidates == null) return null;
        File newest = null;
        for (File candidate : candidates) {
            if (!isFinalCacheFile(candidate)) continue;
            if (newest == null || candidate.lastModified() > newest.lastModified()) newest = candidate;
        }
        return newest;
    }

    /** 非完成态任务即使磁盘上残留同名文件，也不能直接命中本地播放。 */
    static boolean isCompletedTask(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            return task != null && task.status == TaskStatus.COMPLETED
                    && isFinalCacheFile(pathFile(task.targetPath));
        }
    }

    private static boolean isRejectedContentType(String contentType) {
        if (TextUtils.isEmpty(contentType)) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html") || lower.contains("application/json")
                || lower.contains("mpegurl") || lower.contains("dash+xml");
    }

    static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503
                || statusCode == 504;
    }

    private static boolean waitBeforeDirectRetry(DownloadHandle handle, int attempt) {
        if (handle == null || handle.cancelled) return false;
        long delay = Math.min(4_000L, DIRECT_RETRY_BASE_MS * (1L << Math.min(attempt, 3)));
        try {
            Thread.sleep(delay);
            return !handle.cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static File targetFile(Context context, String key) {
        return targetFile(context, key, null);
    }

    static File targetFile(Context context, String key, String remoteUrl) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        return new File(directory, MD5.string2MD5(key) + cacheExtension(remoteUrl));
    }

    static File targetFileForExtension(Context context, String key, String extension) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        String safeExtension = extension == null || extension.isEmpty() ? ".bin" : extension;
        if (!safeExtension.startsWith(".")) safeExtension = "." + safeExtension;
        return new File(directory, MD5.string2MD5(key) + safeExtension.toLowerCase(Locale.ROOT));
    }

    static boolean isPausedTask(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            return task != null && task.status == TaskStatus.PAUSED;
        }
    }

    static void updateTaskTarget(Context context, String id, File target) {
        if (context == null || TextUtils.isEmpty(id) || target == null) return;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null) return;
            task.targetPath = target.getAbsolutePath();
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    private static String cacheExtension(String remoteUrl) {
        String extension = explicitMediaExtension(remoteUrl);
        return extension == null || extension.isEmpty() ? ".mp4" : extension;
    }

    private static void ensureTasksLoaded(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        String currentFilesDirectory = appContext.getFilesDir().getAbsolutePath();
        if (tasksLoaded && currentFilesDirectory.equals(loadedFilesDirectory)) return;
        synchronized (TASK_LOCK) {
            if (tasksLoaded && currentFilesDirectory.equals(loadedFilesDirectory)) return;
            // 某些测试/包装 Context 没有 applicationContext；缓存服务只需要 filesDir，
            // 回退到传入 Context 可避免命中缓存时直接 NPE。
            TASKS.clear();
            PROGRESS_STATES.clear();
            File directory = new File(appContext.getFilesDir(), DIRECTORY);
            taskIndexFile = new File(directory, INDEX_FILE);
            loadedFilesDirectory = currentFilesDirectory;
            if (taskIndexFile.isFile()) {
                try {
                    String text = new String(java.nio.file.Files.readAllBytes(taskIndexFile.toPath()), StandardCharsets.UTF_8);
                    org.json.JSONArray array = new org.json.JSONArray(text);
                    Map<String, TaskRecord> loadedTasks = new LinkedHashMap<>();
                    for (int i = 0; i < array.length(); i++) {
                        TaskRecord task = TaskRecord.fromJson(array.optJSONObject(i));
                        if (task == null || TextUtils.isEmpty(task.id)) continue;
                        task.remoteUrl = normalizeRemoteUrl(task.remoteUrl);
                        task.movieName = nonEmpty(task.movieName, task.movieId);
                        task.episodeName = nonEmpty(task.episodeName, defaultEpisodeName(task.episodeIndex));
                        task.sourceName = nonEmpty(task.sourceName, task.sourceKey);
                        // 旧任务 ID 包含 URL；启动时统一迁移到稳定业务键，避免同一集因
                        // 签名变化留下多条历史任务。
                        task.id = buildCacheKey(task.sourceKey, task.movieId, task.playFlag,
                                task.episodeIndex, task.remoteUrl);
                        if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.QUEUED) {
                            task.status = TaskStatus.FAILED;
                            task.errorMessage = "应用关闭时任务未完成";
                        }
                        TaskRecord previous = loadedTasks.get(task.id);
                        if (previous == null || preferLoadedTask(task, previous)) {
                            loadedTasks.put(task.id, task);
                        }
                    }
                    TASKS.putAll(loadedTasks);
                } catch (Throwable ignored) {
                    LOG.i("offline-task-index-read-failed " + ignored.getMessage());
                }
            }
            tasksLoaded = true;
            persistTasksLocked();
        }
    }

    private static boolean preferLoadedTask(TaskRecord candidate, TaskRecord previous) {
        boolean candidateCompleted = candidate.status == TaskStatus.COMPLETED
                && isUsableCacheFile(pathFile(candidate.targetPath));
        boolean previousCompleted = previous.status == TaskStatus.COMPLETED
                && isUsableCacheFile(pathFile(previous.targetPath));
        if (candidateCompleted != previousCompleted) return candidateCompleted;
        if (candidate.status == TaskStatus.COMPLETED && previous.status != TaskStatus.COMPLETED) return true;
        if (previous.status == TaskStatus.COMPLETED && candidate.status != TaskStatus.COMPLETED) return false;
        return candidate.updatedAt >= previous.updatedAt;
    }

    private static File pathFile(String path) {
        return TextUtils.isEmpty(path) ? null : new File(path);
    }

    private static void registerTask(Context context, String id, String sourceKey, String movieId,
                                     String playFlag, int episodeIndex, String remoteUrl,
                                     Map<String, String> headers, File target) {
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord old = TASKS.get(id);
            if (old == null) {
                old = new TaskRecord();
                old.id = id;
                old.sourceKey = safe(sourceKey);
                old.movieId = safe(movieId);
                old.playFlag = safe(playFlag);
                old.episodeIndex = episodeIndex;
                old.remoteUrl = normalizeRemoteUrl(remoteUrl);
                old.movieName = nonEmpty(movieId, "未命名影片");
                old.episodeName = defaultEpisodeName(episodeIndex);
                old.sourceName = nonEmpty(sourceKey, "未知来源");
                old.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
                old.targetPath = target == null ? "" : target.getAbsolutePath();
                old.status = TaskStatus.QUEUED;
                old.updatedAt = System.currentTimeMillis();
                TASKS.put(id, old);
            } else {
                old.targetPath = target == null ? old.targetPath : target.getAbsolutePath();
                String currentUrl = normalizeRemoteUrl(remoteUrl);
                if (!TextUtils.isEmpty(currentUrl)
                        && old.status != TaskStatus.DOWNLOADING) old.remoteUrl = currentUrl;
                if (!TextUtils.isEmpty(sourceKey)) old.sourceKey = safe(sourceKey);
                if (!TextUtils.isEmpty(movieId)) old.movieId = safe(movieId);
                if (!TextUtils.isEmpty(playFlag)) old.playFlag = safe(playFlag);
                if (TextUtils.isEmpty(old.movieName)) old.movieName = nonEmpty(old.movieId, "未命名影片");
                if (TextUtils.isEmpty(old.episodeName)) old.episodeName = defaultEpisodeName(old.episodeIndex);
                if (TextUtils.isEmpty(old.sourceName)) old.sourceName = nonEmpty(old.sourceKey, "未知来源");
                if (headers != null && !headers.isEmpty()) old.headers = new HashMap<>(headers);
                if (old.status == TaskStatus.FAILED || old.status == TaskStatus.CANCELLED) {
                    old.status = TaskStatus.QUEUED;
                    old.errorMessage = "";
                    old.downloadedBytes = 0;
                    old.totalBytes = -1;
                    old.progressPercent = -1;
                }
            }
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    static void registerTransformerTask(Context context, String id, String sourceKey, String movieId,
                                        String playFlag, int episodeIndex, String remoteUrl,
                                        Map<String, String> headers, File target,
                                        DownloadHandle handle) {
        registerTask(context, id, sourceKey, movieId, playFlag, episodeIndex, remoteUrl, headers, target);
        if (handle != null) ALL_ACTIVE.put(id, handle);
    }

    static void unregisterActive(String id, DownloadHandle handle) {
        if (id != null && handle != null) ALL_ACTIVE.remove(id, handle);
    }

    static void markDownloading(Context context, String id, long totalBytes) {
        markDownloading(context, id, totalBytes, null);
    }

    static void markDownloading(Context context, String id, long totalBytes,
                                @Nullable DownloadHandle handle) {
        if (!isCurrentHandle(id, handle)) return;
        PROGRESS_STATES.remove(id);
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task != null && isCurrentHandle(id, handle)) {
                // 新一轮下载不能沿用上一次任务的字节数、总量或百分比。
                task.downloadedBytes = 0;
                task.totalBytes = totalBytes > 0 ? totalBytes : -1;
                task.estimatedTotalBytes = -1;
                task.progressPercent = -1;
                task.speedBytesPerSecond = 0;
            }
        }
        updateTask(context, id, TaskStatus.DOWNLOADING, 0, totalBytes, "", handle);
    }

    /**
     * HLS 分片续传专用的开始状态更新。分片已经落盘时不能像新任务一样清零，
     * 否则暂停后继续会先显示 0%，并且旧进度会被索引覆盖。
     */
    static void markDownloadingForResume(Context context, String id, long totalBytes,
                                         @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id) || !isCurrentHandle(id, handle)) return;
        PROGRESS_STATES.remove(id);
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle)) return;
            task.status = TaskStatus.DOWNLOADING;
            if (totalBytes > 0) {
                task.totalBytes = totalBytes;
                task.estimatedTotalBytes = -1;
            }
            else if (task.totalBytes < 0) task.totalBytes = -1;
            if (task.downloadedBytes <= 0) task.progressPercent = -1;
            else if (task.progressPercent > 99) task.progressPercent = 99;
            task.speedBytesPerSecond = 0;
            task.errorMessage = "";
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    static void markProgress(Context context, String id, long downloadedBytes, long totalBytes) {
        markProgress(context, id, downloadedBytes, totalBytes, true, null);
    }

    /**
     * 更新下载字节数；当总量尚未完整探测时只记录字节数，不计算展示百分比。
     * 这对并行 HLS 分片尤其重要：此时已知总量只是已返回长度的分片之和。
     */
    static void markProgress(Context context, String id, long downloadedBytes, long totalBytes,
                             boolean totalComplete) {
        markProgress(context, id, downloadedBytes, totalBytes, totalComplete, null);
    }

    static void markProgress(Context context, String id, long downloadedBytes, long totalBytes,
                             boolean totalComplete, @Nullable DownloadHandle handle) {
        markProgressAt(context, id, downloadedBytes, totalBytes, totalComplete,
                System.currentTimeMillis(), System.nanoTime(), handle);
    }

    // 纯 JVM 单测使用确定性的时间序列，生产下载路径仍统一取系统时钟。
    static void markProgressForTests(Context context, String id, long downloadedBytes,
                                     long totalBytes, long nowMillis, long nowNanos) {
        markProgressAt(context, id, downloadedBytes, totalBytes, true, nowMillis, nowNanos);
    }

    private static void markProgressAt(Context context, String id, long downloadedBytes,
                                       long totalBytes, boolean totalComplete, long now,
                                       long nowNanos) {
        markProgressAt(context, id, downloadedBytes, totalBytes, totalComplete, now, nowNanos,
                null);
    }

    private static void markProgressAt(Context context, String id, long downloadedBytes,
                                       long totalBytes, boolean totalComplete, long now,
                                       long nowNanos, @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        ensureTasksLoaded(context);
        boolean flush;
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle)) return;
            if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED
                    || task.status == TaskStatus.CANCELLED || task.status == TaskStatus.PAUSED) return;
            if (task.progressPercent > 99) task.progressPercent = 99;

            // 分片回调可能乱序，任务记录中的进度只能单调增加。
            long previousDownloaded = task.downloadedBytes;
            long stableDownloaded = downloadedBytes >= 0
                    ? Math.max(task.downloadedBytes, downloadedBytes) : task.downloadedBytes;
            // 可靠的 Content-Length 变化时允许重校准；未知总量回调不覆盖已有值。
            long previousTotal = task.totalBytes;
            long stableTotal = previousTotal;
            if (totalComplete && totalBytes > 0) stableTotal = totalBytes;
            boolean totalRecalibrated = totalComplete && stableTotal > 0
                    && stableTotal != previousTotal;
            if (totalComplete && stableTotal > 0) task.estimatedTotalBytes = -1;
            task.status = TaskStatus.DOWNLOADING;
            task.downloadedBytes = stableDownloaded;
            task.totalBytes = stableTotal;
            if (totalComplete && stableTotal > 0) {
                long candidate = stableDownloaded * 100L / stableTotal;
                // 只有最终文件原子提交后才允许进入 100%；下载中最高显示 99%。
                int safeCandidate = (int) Math.max(0, Math.min(99, candidate));
                task.progressPercent = totalRecalibrated
                        ? safeCandidate : Math.max(task.progressPercent, safeCandidate);
            } else if (task.progressPercent < 0) {
                task.progressPercent = -1;
            }
            task.errorMessage = "";

            ProgressState state = PROGRESS_STATES.get(id);
            if (state == null) {
                state = new ProgressState(now, nowNanos, stableDownloaded);
                PROGRESS_STATES.put(id, state);
                task.speedBytesPerSecond = 0;
                task.updatedAt = now;
                flush = true;
            } else {
                boolean sampleAdded = false;
                // 超过窗口没有真实增量时，下一次回调只能建立新基线，不能把跨停顿的
                // 字节差平均到更长时间后继续冒充当前速度。
                if (nowNanos >= state.lastSampleAtNanos
                        && nowNanos - state.lastSampleAtNanos > SPEED_WINDOW_NS) {
                    state.samples.clear();
                    state.samples.addLast(new SpeedSample(nowNanos, stableDownloaded));
                    state.lastSampleAtNanos = nowNanos;
                    state.lastSampleBytes = stableDownloaded;
                    task.speedBytesPerSecond = 0;
                } else if (stableDownloaded > state.lastSampleBytes
                        && nowNanos > state.lastSampleAtNanos) {
                    state.samples.addLast(new SpeedSample(nowNanos, stableDownloaded));
                    state.lastSampleAtNanos = nowNanos;
                    state.lastSampleBytes = stableDownloaded;
                    sampleAdded = true;
                }
                // 保留一个至少覆盖 1 秒的单调字节/单调时间窗口；重复或乱序回调不会新增样本。
                while (state.samples.size() >= 2) {
                    Iterator<SpeedSample> iterator = state.samples.iterator();
                    iterator.next();
                    SpeedSample second = iterator.next();
                    if (nowNanos - second.atNanos >= SPEED_WINDOW_NS) {
                        state.samples.removeFirst();
                    } else {
                        break;
                    }
                }
                while (state.samples.size() > MAX_SPEED_SAMPLES) state.samples.removeFirst();
                if (sampleAdded && !state.samples.isEmpty()) {
                    SpeedSample first = state.samples.peekFirst();
                    long elapsed = nowNanos - first.atNanos;
                    long delta = stableDownloaded - first.bytes;
                    if (elapsed >= SPEED_MIN_SAMPLE_NS && delta >= 0) {
                        long instantaneous = delta * 1_000_000_000L / elapsed;
                        long previousSpeed = task.speedBytesPerSecond;
                        // 采用与 iOS 下载器相同的指数平滑，避免分片回调把速度显示成锯齿。
                        task.speedBytesPerSecond = previousSpeed > 0
                                ? (previousSpeed * 65L + instantaneous * 35L) / 100L
                                : instantaneous;
                    }
                }
                if (stableDownloaded > previousDownloaded) task.updatedAt = now;
                flush = now - state.lastFlushAt >= PROGRESS_FLUSH_INTERVAL_MS
                        || downloadedBytes < 0
                        || stableDownloaded - state.lastFlushedBytes >= PROGRESS_FLUSH_BYTES
                        || (stableTotal >= 0 && stableTotal != state.lastTotalBytes);
            }
            if (flush) {
                state.lastFlushAt = now;
                state.lastFlushedBytes = stableDownloaded;
                state.lastTotalBytes = stableTotal;
                persistTasksLocked();
            }
        }
        if (flush) notifyListeners(context);
    }

    /**
     * HLS 分片下载没有可靠的字节总量时，按已完成分片数量展示阶段性进度；
     * totalBytes 仍保持未知，避免把已探测分片大小误当完整文件大小。
     */
    static void markSegmentProgress(Context context, String id, int completedSegments,
                                    int totalSegments, long downloadedBytes) {
        markSegmentProgress(context, id, completedSegments, totalSegments, downloadedBytes, null);
    }

    static void markSegmentProgress(Context context, String id, int completedSegments,
                                    int totalSegments, long downloadedBytes,
                                    @Nullable DownloadHandle handle) {
        if (totalSegments <= 0) {
            markProgress(context, id, downloadedBytes, -1, false, handle);
            return;
        }
        markProgress(context, id, downloadedBytes, -1, false, handle);
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle) || task.status == TaskStatus.PAUSED
                    || task.status == TaskStatus.CANCELLED
                    || task.status == TaskStatus.FAILED
                    || task.status == TaskStatus.COMPLETED) return;
            if (task.progressPercent > 99) task.progressPercent = 99;
            int safeCompleted = Math.max(0, Math.min(completedSegments, totalSegments));
            // 分片全部下载后仍需合并并原子提交，完成态之前最多显示 99%。
            task.progressPercent = Math.max(task.progressPercent,
                    Math.min(99, safeCompleted * 100 / totalSegments));
            if (task.totalBytes <= 0) task.totalBytes = -1;
            task.updatedAt = Math.max(System.currentTimeMillis(), task.updatedAt + 1L);
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    static void markSegmentProgressByDuration(Context context, String id, int completedSegments,
                                             int totalSegments, long downloadedBytes,
                                             long completedDurationMs, long totalDurationMs,
                                             long completedBytes,
                                             @Nullable DownloadHandle handle) {
        markProgress(context, id, downloadedBytes, -1L, false, handle);
        if (context == null || TextUtils.isEmpty(id) || totalSegments <= 0
                || completedDurationMs <= 0L || totalDurationMs <= 0L
                || completedDurationMs > totalDurationMs) {
            markSegmentProgress(context, id, completedSegments, totalSegments,
                    downloadedBytes, handle);
            return;
        }
        long candidateTotal = estimateTotalBytesFromDuration(
                completedBytes, completedDurationMs, totalDurationMs);
        if (!isCurrentHandle(id, handle)) return;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle) || task.status == TaskStatus.PAUSED
                    || task.status == TaskStatus.CANCELLED || task.status == TaskStatus.FAILED
                    || task.status == TaskStatus.COMPLETED) return;
            int safePercent = (int) Math.min(99L,
                    Math.max(0L, completedDurationMs * 100L / totalDurationMs));
            task.progressPercent = Math.max(task.progressPercent, safePercent);
            if (task.totalBytes <= 0L && candidateTotal > 0L
                    && completedDurationMs >= MIN_DURATION_ESTIMATE_SAMPLE_MS) {
                task.estimatedTotalBytes = candidateTotal;
            }
            task.updatedAt = Math.max(System.currentTimeMillis(), task.updatedAt + 1L);
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    static long estimateTotalBytesFromDuration(long completedBytes, long completedDurationMs,
                                               long totalDurationMs) {
        if (completedBytes <= 0L || completedDurationMs < MIN_DURATION_ESTIMATE_SAMPLE_MS
                || totalDurationMs < completedDurationMs) return -1L;
        double estimate = completedBytes * (double) totalDurationMs / completedDurationMs;
        if (!Double.isFinite(estimate) || estimate <= 0.0 || estimate > MAX_FILE_BYTES) return -1L;
        return Math.max(completedBytes, Math.round(estimate));
    }

    /** Media3 Transformer 只提供百分比，不提供文件总字节数；保留真实字节速度并单独更新百分比。 */
    static void markTransformerProgress(Context context, String id, int progressPercent) {
        markTransformerProgress(context, id, progressPercent, null);
    }

    static void markTransformerProgress(Context context, String id, int progressPercent,
                                        @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        ensureTasksLoaded(context);
        boolean changed = false;
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle) || task.status == TaskStatus.COMPLETED
                    || task.status == TaskStatus.FAILED
                    || task.status == TaskStatus.CANCELLED
                    || task.status == TaskStatus.PAUSED) return;
            if (task.progressPercent > 99) task.progressPercent = 99;
            int safePercent = Math.max(0, Math.min(99, progressPercent));
            if (task.status != TaskStatus.DOWNLOADING) {
                task.status = TaskStatus.DOWNLOADING;
                changed = true;
            }
            if (safePercent > task.progressPercent) {
                task.progressPercent = safePercent;
                changed = true;
            }
            if (changed) {
                task.errorMessage = "";
                task.updatedAt = System.currentTimeMillis();
                persistTasksLocked();
            }
        }
        if (changed) notifyListeners(context);
    }

    static void markCompleted(Context context, String id, File target) {
        markCompleted(context, id, target, null);
    }

    static void markCompleted(Context context, String id, File target,
                              @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        PROGRESS_STATES.remove(id);
        // 只有最终文件真实存在且非空时才能进入完成态。不能使用旧进度或
        // Content-Length 兜底，否则暂停/重试会把未完成任务误报为已完成。
        if (!isFinalCacheFile(target)) {
            markFailed(context, id, "缓存文件未完整落盘", handle);
            return;
        }
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle)) return;
            // 只有本轮已经真正进入 DOWNLOADING 才允许提交完成态。
            // QUEUED/PAUSED 是“继续前”的中间态，旧回调即使带着完整残留文件
            // 到达，也不能把它直接推进为 COMPLETED。
            if (task.status != TaskStatus.DOWNLOADING) {
                if (target.isFile()) target.delete();
                return;
            }
            long finalBytes = target.length();
            task.targetPath = target.getAbsolutePath();
            if (TextUtils.isEmpty(task.targetPath)) task.targetPath = target.getAbsolutePath();
            task.status = TaskStatus.COMPLETED;
            task.downloadedBytes = finalBytes;
            task.totalBytes = finalBytes;
            task.estimatedTotalBytes = -1L;
            task.progressPercent = 100;
            task.speedBytesPerSecond = 0;
            task.errorMessage = "";
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    static void markFailed(Context context, String id, String error) {
        markFailed(context, id, error, null);
    }

    static void markFailed(Context context, String id, String error,
                           @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        PROGRESS_STATES.remove(id);
        updateTask(context, id, TaskStatus.FAILED, -1, -1,
                TextUtils.isEmpty(error) ? "下载失败" : error, handle);
    }

    static void markCancelled(Context context, String id) {
        markCancelled(context, id, null);
    }

    static void markCancelled(Context context, String id, @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        PROGRESS_STATES.remove(id);
        updateTask(context, id, TaskStatus.CANCELLED, -1, -1, "已取消", handle);
    }

    private static void updateTask(Context context, String id, TaskStatus status,
                                   long downloadedBytes, long totalBytes, String error) {
        updateTask(context, id, status, downloadedBytes, totalBytes, error, null);
    }

    private static void updateTask(Context context, String id, TaskStatus status,
                                   long downloadedBytes, long totalBytes, String error,
                                   @Nullable DownloadHandle handle) {
        if (context == null || TextUtils.isEmpty(id)) return;
        if (!isCurrentHandle(id, handle)) return;
        ensureTasksLoaded(context);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || !isCurrentHandle(id, handle)) return;
            // 暂停回调会在后台取消后到达，不能把 PAUSED 回退成 CANCELLED。
            if (status == TaskStatus.CANCELLED && task.status == TaskStatus.PAUSED) return;
            if (status == TaskStatus.DOWNLOADING && task.progressPercent > 99) {
                task.progressPercent = 99;
            }
            task.status = status;
            if (status == TaskStatus.DOWNLOADING) {
                if (downloadedBytes >= 0) task.downloadedBytes = Math.max(task.downloadedBytes, downloadedBytes);
                if (totalBytes > 0) task.totalBytes = Math.max(task.totalBytes, totalBytes);
                if (task.totalBytes > 0) {
                    long candidate = task.downloadedBytes * 100L / task.totalBytes;
                    int safeCandidate = (int) Math.max(0, Math.min(99, candidate));
                    task.progressPercent = Math.max(task.progressPercent, safeCandidate);
                }
            } else {
                if (downloadedBytes >= 0) task.downloadedBytes = downloadedBytes;
                if (totalBytes >= 0) task.totalBytes = totalBytes;
            }
            if (status != TaskStatus.DOWNLOADING) task.speedBytesPerSecond = 0;
            task.errorMessage = error == null ? "" : error;
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    public static List<TaskSnapshot> getTaskSnapshots(Context context) {
        if (context == null) return Collections.emptyList();
        ensureTasksLoaded(context);
        syncJianpianProgress(context);
        synchronized (TASK_LOCK) {
            List<TaskSnapshot> result = new ArrayList<>();
            boolean changed = false;
            for (TaskRecord task : TASKS.values()) {
                File target = task.targetPath == null ? null : new File(task.targetPath);
                if (task.status == TaskStatus.COMPLETED
                        && !TextUtils.isEmpty(task.targetPath)
                        && (target == null || !isFinalCacheFile(target))) {
                    // 只允许真实最终文件支持完成态；索引残留不能制造“已完成”任务。
                    task.status = TaskStatus.FAILED;
                    task.errorMessage = "缓存文件不存在";
                    task.progressPercent = -1;
                    task.speedBytesPerSecond = 0;
                    task.updatedAt = System.currentTimeMillis();
                    changed = true;
                }
                result.add(task.snapshot());
            }
            if (changed) persistTasksLocked();
            // TASKS 是 LinkedHashMap，插入顺序就是任务首次加入下载列表的顺序。
            // 只倒置这份快照，不按 updatedAt 排序；暂停、继续、进度回调和完成回调
            // 都不会改变任务在列表中的位置，新加入的任务始终显示在最上方。
            Collections.reverse(result);
            return result;
        }
    }

    /**
     * P2P 桥接的 HTTP 读取速度不等于原生任务速度：播放器/下载器可能在等待下一段
     * 数据时没有回调，但 libp2p 已经把 .ybbk 写大。读取快照前补一次原生字节数，
     * 让 UI 显示实际任务进度，而不是只显示“等待速度”。
     */
    private static void syncJianpianProgress(Context context) {
        if (context == null) return;
        List<String> ids = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        synchronized (TASK_LOCK) {
            for (TaskRecord task : TASKS.values()) {
                if ((task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.QUEUED)
                        && isJianpianDownloadUrl(task.remoteUrl)) {
                    ids.add(task.id);
                    urls.add(task.remoteUrl);
                }
            }
        }
        if (ids.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (TASK_LOCK) {
            for (int i = 0; i < ids.size(); i++) {
                TaskRecord task = TASKS.get(ids.get(i));
                if (task == null || (!task.status.equals(TaskStatus.DOWNLOADING)
                        && !task.status.equals(TaskStatus.QUEUED))) continue;
                Jianpian.DownloadStats stats = Jianpian.queryDownloadStats(urls.get(i));
                if (stats == null || stats.downloadedBytes <= 0L) continue;
                long previousBytes = task.downloadedBytes;
                long previousTotal = task.totalBytes;
                long downloaded = Math.max(previousBytes, stats.downloadedBytes);
                long total = stats.totalBytes > 0L
                        ? Math.max(previousTotal, stats.totalBytes) : previousTotal;
                if (task.status == TaskStatus.QUEUED) task.status = TaskStatus.DOWNLOADING;
                task.downloadedBytes = downloaded;
                if (total > 0L) {
                    task.totalBytes = total;
                    task.progressPercent = Math.max(Math.min(task.progressPercent, 99),
                            (int) Math.min(99L, downloaded * 100L / total));
                }
                if (stats.speedBytesPerSecond > 0L) {
                    task.speedBytesPerSecond = stats.speedBytesPerSecond;
                }
                if (downloaded > previousBytes || total != previousTotal) {
                    task.updatedAt = now;
                    changed = true;
                }
            }
            if (changed) persistTasksLocked();
        }
    }

    /**
     * 为详情页批量下载补充展示元数据。缓存文件命名和缓存键保持不变，
     * 下载页可据此显示影片名、集名和资源站名称。
     */
    public static void setTaskMetadata(Context context, String sourceKey, String movieId,
                                       String playFlag, int episodeIndex, String remoteUrl,
                                       String movieName, String episodeName, String sourceName) {
        if (context == null) return;
        ensureTasksLoaded(context);
        remoteUrl = normalizeRemoteUrl(remoteUrl);
        if (TextUtils.isEmpty(remoteUrl)) return;
        String id = buildCacheKey(sourceKey, movieId, playFlag, episodeIndex, remoteUrl);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null) return;
            // 元数据可能先于异步下载注册到达；保留此分支用于已存在任务。
            task.movieName = nonEmpty(movieName, task.movieId);
            task.episodeName = nonEmpty(episodeName, defaultEpisodeName(task.episodeIndex));
            task.sourceName = nonEmpty(sourceName, task.sourceKey);
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    /**
     * 在启动下载前登记展示元数据，避免高速任务完成后才补写元数据造成竞态。
     * 已有任务只更新元数据，不重置状态、进度或目标文件。
     */
    public static void prepareTaskMetadata(Context context, String sourceKey, String movieId,
                                           String playFlag, int episodeIndex, String remoteUrl,
                                           String movieName, String episodeName, String sourceName) {
        if (context == null) return;
        ensureTasksLoaded(context);
        remoteUrl = normalizeRemoteUrl(remoteUrl);
        if (TextUtils.isEmpty(remoteUrl)) return;
        String id = buildCacheKey(sourceKey, movieId, playFlag, episodeIndex, remoteUrl);
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null) {
                task = new TaskRecord();
                task.id = id;
                task.sourceKey = safe(sourceKey);
                task.movieId = safe(movieId);
                task.playFlag = safe(playFlag);
                task.episodeIndex = episodeIndex;
                task.remoteUrl = remoteUrl;
                task.status = TaskStatus.QUEUED;
                task.targetPath = "";
                task.updatedAt = System.currentTimeMillis();
                TASKS.put(id, task);
            } else if (task.status != TaskStatus.DOWNLOADING) {
                // 任务尚未真正开始或上一轮已结束时，允许刷新到最新签名地址；
                // 正在下载的任务保持原地址，避免重试/回调串到另一条流。
                task.remoteUrl = remoteUrl;
                if (!TextUtils.isEmpty(playFlag)) task.playFlag = safe(playFlag);
            }
            task.movieName = nonEmpty(movieName, task.movieId);
            task.episodeName = nonEmpty(episodeName, defaultEpisodeName(task.episodeIndex));
            task.sourceName = nonEmpty(sourceName, task.sourceKey);
            task.updatedAt = System.currentTimeMillis();
            persistTasksLocked();
        }
        notifyListeners(context);
    }

    public static void addTaskListener(Context context, TaskListener listener) {
        if (listener == null) return;
        ensureTasksLoaded(context);
        LISTENERS.add(listener);
        listener.onTasksChanged(getTaskSnapshots(context));
    }

    public static void removeTaskListener(TaskListener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void notifyListeners(Context context) {
        if (LISTENERS.isEmpty()) return;
        List<TaskSnapshot> snapshot = getTaskSnapshots(context);
        for (TaskListener listener : LISTENERS) {
            try { listener.onTasksChanged(snapshot); } catch (Throwable ignored) { }
        }
    }

    public static boolean retry(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        ensureTasksLoaded(context);
        // 继续/重试前先切断同一业务键残留的旧句柄。iOS 的 resumeData 只允许
        // 一个当前任务持有，旧任务不能在新任务开始后再提交完成回调。
        DownloadHandle staleHandle = ALL_ACTIVE.get(id);
        if (staleHandle != null) {
            // 暂停句柄已经完成 cancel，不能再次调用 cancel()，否则会清理本应
            // 用于断点续传的 .part/HLS 分片；非暂停残留句柄才需要强制取消。
            if (!staleHandle.isPauseRequested()) staleHandle.cancel();
            staleHandle.awaitStopped();
            ALL_ACTIVE.remove(id, staleHandle);
            ACTIVE.remove(id, staleHandle);
        }
        // 重试必须从全新的采样基线开始，不能让上一轮取消前的样本参与新一轮速度。
        PROGRESS_STATES.remove(id);
        String sourceKey;
        String movieId;
        String playFlag;
        int episodeIndex;
        String remoteUrl;
        Map<String, String> headers;
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            if (task == null || TextUtils.isEmpty(task.remoteUrl)) return false;
            sourceKey = task.sourceKey;
            movieId = task.movieId;
            playFlag = task.playFlag;
            episodeIndex = task.episodeIndex;
            remoteUrl = task.remoteUrl;
            headers = task.headers == null ? new HashMap<>() : new HashMap<>(task.headers);
            File existingTarget = TextUtils.isEmpty(task.targetPath) ? null : new File(task.targetPath);
            if (task.status == TaskStatus.COMPLETED
                    && existingTarget != null && isFinalCacheFile(existingTarget)) {
                task.status = TaskStatus.COMPLETED;
                task.downloadedBytes = existingTarget.length();
                task.totalBytes = existingTarget.length();
                task.progressPercent = 100;
                task.speedBytesPerSecond = 0;
                task.errorMessage = "";
                task.updatedAt = System.currentTimeMillis();
                persistTasksLocked();
                return true;
            }
            // 非完成态出现同名最终文件时按残留文件处理，不能让下面的 prefetch
            // 通过缓存命中分支把它重新标为完成；.part 与 HLS 已完成分片仍保留用于续传。
            File staleTarget = findExistingTarget(context, task.id, remoteUrl);
            if (isFinalCacheFile(staleTarget)) staleTarget.delete();
            if (existingTarget != null && isFinalCacheFile(existingTarget)) existingTarget.delete();
            File partialTarget = existingTarget;
            if (partialTarget == null) {
                partialTarget = targetFile(context, task.id, remoteUrl);
            }
            File partial = partialTarget == null ? null
                    : new File(partialTarget.getParentFile(), partialTarget.getName() + ".part");
            long partialBytes = partial != null && partial.isFile() ? Math.max(0, partial.length()) : 0;
            // HLS 直拼下载把完整分片写成 target.seg-N，暂停时这些文件就是
            // 可复用的断点数据；不能只检查 target.part，否则继续时进度会归零。
            long reusableSegmentBytes = reusableSegmentBytes(context, task.id);
            if (reusableSegmentBytes > 0) {
                task.downloadedBytes = reusableSegmentBytes;
                task.totalBytes = -1;
                task.progressPercent = task.progressPercent < 0
                        ? -1 : Math.min(99, task.progressPercent);
            } else {
                // 只有磁盘上确实存在 .part 才保留断点字节；仅来自旧回调的进度
                // 不代表可续传，必须清零，避免重试时出现虚假的已下载百分比。
                task.downloadedBytes = partialBytes > 0 ? partialBytes : 0;
                task.totalBytes = partialBytes > 0 && task.totalBytes > partialBytes
                        ? task.totalBytes : -1;
                task.progressPercent = task.totalBytes > 0
                        ? (int) Math.max(0, Math.min(99,
                        task.downloadedBytes * 100L / task.totalBytes)) : -1;
            }
            task.speedBytesPerSecond = 0;
            task.status = TaskStatus.QUEUED;
            task.errorMessage = "";
            persistTasksLocked();
        }
        prefetch(context, sourceKey, movieId, playFlag, episodeIndex, remoteUrl, headers);
        return true;
    }

    /** 汇总同一任务目录中已经原子落盘的 HLS 分片，忽略 .part 临时文件。 */
    private static long reusableSegmentBytes(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return 0L;
        File directory = new File(context.getFilesDir(), DIRECTORY);
        String prefix = MD5.string2MD5(id);
        File[] files = directory.listFiles((dir, name) -> name != null
                && name.startsWith(prefix) && name.contains(".seg-")
                && !name.endsWith(".part"));
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (isReusableSegmentFile(file)) {
                long length = file.length();
                if (length > 0 && total <= MAX_FILE_BYTES - length) total += length;
            }
        }
        return total;
    }

    private static boolean isReusableSegmentFile(File file) {
        return file != null && file.isFile() && file.length() > 0
                && !file.getName().endsWith(".part");
    }

    public static boolean delete(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        ensureTasksLoaded(context);
        DownloadHandle active = ALL_ACTIVE.remove(id);
        String remoteUrl = taskRemoteUrl(id);
        if (active != null) {
            active.cancel();
            if (isJianpianDownloadUrl(remoteUrl)) Jianpian.cancelDownloadUrl(remoteUrl);
            active.awaitStopped();
        }
        PROGRESS_STATES.remove(id);
        TaskRecord task;
        Set<String> retainedPaths = new HashSet<>();
        synchronized (TASK_LOCK) {
            task = TASKS.get(id);
            if (task == null) return false;
            // 同一缓存文件可能被旧版任务记录或重复任务引用；只有没有其他引用时才能物理删除。
            for (TaskRecord other : TASKS.values()) {
                if (other == task || TextUtils.isEmpty(other.targetPath)) continue;
                retainedPaths.add(new File(other.targetPath).getAbsolutePath());
            }
            TASKS.remove(id);
            persistTasksLocked();
        }
        deleteTaskArtifacts(context, task, retainedPaths);
        if (active == null && isJianpianDownloadUrl(remoteUrl)) {
            Jianpian.cancelDownloadUrl(remoteUrl);
        }
        notifyListeners(context);
        return true;
    }

    /** 删除任务所有可能的缓存扩展名、临时文件和 HLS 分片，不触碰其他任务引用的文件。 */
    private static void deleteTaskArtifacts(Context context, TaskRecord task,
                                            Set<String> retainedPaths) {
        if (context == null || task == null) return;
        Set<File> candidates = new HashSet<>();
        if (!TextUtils.isEmpty(task.targetPath)) candidates.add(new File(task.targetPath));

        String key = task.id;
        if (TextUtils.isEmpty(key)) {
            key = buildCacheKey(task.sourceKey, task.movieId, task.playFlag,
                    task.episodeIndex, task.remoteUrl);
        }
        File directory = new File(context.getFilesDir(), DIRECTORY);
        String prefix = MD5.string2MD5(key);
        File[] files = directory.listFiles((dir, name) -> name != null && name.startsWith(prefix));
        if (files != null) {
            for (File file : files) candidates.add(file);
        }

        for (File candidate : candidates) {
            if (candidate == null) continue;
            String path = candidate.getAbsolutePath();
            if (retainedPaths.contains(path)) continue;
            if (candidate.exists()) candidate.delete();
            OfflineHlsTransformer.deleteSegmentArtifacts(candidate);
            File part = new File(candidate.getParentFile(), candidate.getName() + ".part");
            if (part.exists() && !retainedPaths.contains(part.getAbsolutePath())) part.delete();
        }
        // 分片文件名以最终目标名为前缀；覆盖 targetPath 不在当前目录或旧版扩展名的情况。
        if (!TextUtils.isEmpty(task.targetPath)) {
            File target = new File(task.targetPath);
            if (!retainedPaths.contains(target.getAbsolutePath())) {
                OfflineHlsTransformer.deleteSegmentArtifacts(target);
                File part = new File(target.getParentFile(), target.getName() + ".part");
                if (part.exists() && !retainedPaths.contains(part.getAbsolutePath())) part.delete();
            }
        }
    }

    public static boolean cancel(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        DownloadHandle active = ALL_ACTIVE.get(id);
        if (active == null) return false;
        String remoteUrl = taskRemoteUrl(id);
        // 先摘除当前句柄，再落取消状态；迟到回调从这一刻起全部失效。
        synchronized (TASK_LOCK) {
            ACTIVE.remove(id, active);
            ALL_ACTIVE.remove(id, active);
        }
        markCancelled(context, id);
        active.cancel();
        if (isJianpianDownloadUrl(remoteUrl)) Jianpian.cancelDownloadUrl(remoteUrl);
        return true;
    }

    public static boolean pause(Context context, String id) {
        if (context == null || TextUtils.isEmpty(id)) return false;
        DownloadHandle active = ALL_ACTIVE.get(id);
        if (active == null) return false;
        String remoteUrl = taskRemoteUrl(id);
        // 不提前移除旧句柄。iOS 会等待 cancel/resumeData 回调完成后才允许 resume；
        // 这里保留句柄占位，旧回调因 cancelled 标记自动失效，继续操作会先等待它退出。
        active.pause();
        // 先暂停原生 P2P 生产者，再等待 HTTP 复制器退出，避免等待期间 .ybbk 继续增长。
        if (isJianpianDownloadUrl(remoteUrl)) Jianpian.pauseDownloadUrl(remoteUrl);
        // 等网络流关闭并将缓冲写入 .part，再向 UI 确认暂停。继续操作也会再次
        // 校验停止闩锁，保证不会在上一个写入者释放文件前发起 Range 请求。
        active.awaitStopped();
        // 暂停任务保留在索引中，.part/HLS 完整分片由下一轮继续复用。
        updateTask(context, id, TaskStatus.PAUSED, -1, -1, "已暂停");
        PROGRESS_STATES.remove(id);
        return true;
    }

    private static String taskRemoteUrl(String id) {
        if (TextUtils.isEmpty(id)) return "";
        synchronized (TASK_LOCK) {
            TaskRecord task = TASKS.get(id);
            return task == null ? "" : task.remoteUrl;
        }
    }

    private static boolean isJianpianDownloadUrl(String url) {
        return url != null && url.contains("/jianpian/p2p.m3u8");
    }

    /** 只有当前这一轮下载才能更新任务状态；暂停后旧线程的迟到回调会被丢弃。 */
    static boolean isCurrentHandle(String id, @Nullable DownloadHandle handle) {
        return handle == null || (!handle.isCancelled() && ALL_ACTIVE.get(id) == handle);
    }

    private static void persistTasksLocked() {
        if (taskIndexFile == null) return;
        try {
            File parent = taskIndexFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            org.json.JSONArray array = new org.json.JSONArray();
            for (TaskRecord task : TASKS.values()) array.put(task.toJson());
            File tmp = new File(taskIndexFile.getParentFile(), INDEX_FILE + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (taskIndexFile.exists()) taskIndexFile.delete();
            tmp.renameTo(taskIndexFile);
        } catch (Throwable ignored) {
            LOG.i("offline-task-index-write-failed " + ignored.getMessage());
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").trim();
    }

    private static String nonEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String defaultEpisodeName(int episodeIndex) {
        return "第" + (Math.max(0, episodeIndex) + 1) + "集";
    }

    public enum TaskStatus {
        QUEUED, DOWNLOADING, COMPLETED, FAILED, PAUSED, CANCELLED
    }

    public interface TaskListener {
        void onTasksChanged(List<TaskSnapshot> tasks);
    }

    /** 用于下载页展示的只读任务快照；缓存文件仍保持原有 .mp4 格式。 */
    public static final class TaskSnapshot {
        private final String id;
        private final String sourceKey;
        private final String movieId;
        private final String movieName;
        private final String playFlag;
        private final String episodeName;
        private final String sourceName;
        private final int episodeIndex;
        private final String remoteUrl;
        private final String targetPath;
        private final TaskStatus status;
        private final long downloadedBytes;
        private final long totalBytes;
        private final long estimatedTotalBytes;
        private final int progressPercent;
        private final String errorMessage;
        private final long updatedAt;
        private final long speedBytesPerSecond;

        private TaskSnapshot(TaskRecord task) {
            id = task.id;
            sourceKey = task.sourceKey;
            movieId = task.movieId;
            movieName = task.movieName;
            playFlag = task.playFlag;
            episodeName = task.episodeName;
            sourceName = task.sourceName;
            episodeIndex = task.episodeIndex;
            remoteUrl = task.remoteUrl;
            targetPath = task.targetPath;
            status = task.status;
            downloadedBytes = task.downloadedBytes;
            totalBytes = task.totalBytes;
            estimatedTotalBytes = task.estimatedTotalBytes;
            progressPercent = task.progressPercent;
            errorMessage = task.errorMessage;
            updatedAt = task.updatedAt;
            speedBytesPerSecond = task.speedBytesPerSecond;
        }

        public String getId() { return id; }
        public String getSourceKey() { return sourceKey; }
        public String getMovieId() { return movieId; }
        public String getMovieName() { return movieName; }
        public String getPlayFlag() { return playFlag; }
        public String getEpisodeName() { return episodeName; }
        public String getSourceName() { return sourceName; }
        public int getEpisodeIndex() { return episodeIndex; }
        public String getRemoteUrl() { return remoteUrl; }
        public String getTargetPath() { return targetPath; }
        public TaskStatus getStatus() { return status; }
        public long getDownloadedBytes() { return downloadedBytes; }
        public long getTotalBytes() { return totalBytes; }
        public long getEstimatedTotalBytes() { return estimatedTotalBytes; }
        public String getErrorMessage() { return errorMessage; }
        public long getUpdatedAt() { return updatedAt; }
        public long getSpeedBytesPerSecond() { return speedBytesPerSecond; }

        public int getProgressPercent() {
            if (status == TaskStatus.COMPLETED) return 100;
            if (progressPercent >= 0) return Math.max(0, Math.min(99, progressPercent));
            if (totalBytes <= 0 || downloadedBytes < 0) return -1;
            return (int) Math.max(0, Math.min(99, downloadedBytes * 100L / totalBytes));
        }
    }

    private static final class TaskRecord {
        String id = "";
        String sourceKey = "";
        String movieId = "";
        String movieName = "";
        String playFlag = "";
        String episodeName = "";
        String sourceName = "";
        int episodeIndex;
        String remoteUrl = "";
        String targetPath = "";
        Map<String, String> headers = new HashMap<>();
        TaskStatus status = TaskStatus.QUEUED;
        long downloadedBytes;
        long totalBytes = -1;
        long estimatedTotalBytes = -1;
        int progressPercent = -1;
        String errorMessage = "";
        long updatedAt;
        transient long speedBytesPerSecond;

        TaskSnapshot snapshot() { return new TaskSnapshot(this); }

        org.json.JSONObject toJson() {
            org.json.JSONObject json = new org.json.JSONObject();
            try {
                json.put("id", id);
                json.put("sourceKey", sourceKey);
                json.put("movieId", movieId);
                json.put("movieName", movieName);
                json.put("playFlag", playFlag);
                json.put("episodeName", episodeName);
                json.put("sourceName", sourceName);
                json.put("episodeIndex", episodeIndex);
                json.put("remoteUrl", remoteUrl);
                json.put("targetPath", targetPath);
                json.put("status", status.name());
                json.put("downloadedBytes", downloadedBytes);
                json.put("totalBytes", totalBytes);
                json.put("estimatedTotalBytes", estimatedTotalBytes);
                json.put("progressPercent", progressPercent);
                json.put("errorMessage", errorMessage);
                json.put("updatedAt", updatedAt);
                org.json.JSONObject headerJson = new org.json.JSONObject();
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                            headerJson.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                json.put("headers", headerJson);
            } catch (Throwable ignored) {
            }
            return json;
        }

        static TaskRecord fromJson(org.json.JSONObject json) {
            if (json == null) return null;
            TaskRecord task = new TaskRecord();
            task.id = json.optString("id", "");
            task.sourceKey = json.optString("sourceKey", "");
            task.movieId = json.optString("movieId", "");
            task.movieName = json.optString("movieName", "");
            task.playFlag = json.optString("playFlag", "");
            task.episodeName = json.optString("episodeName", "");
            task.sourceName = json.optString("sourceName", "");
            task.episodeIndex = json.optInt("episodeIndex", 0);
            task.remoteUrl = json.optString("remoteUrl", "");
            task.targetPath = json.optString("targetPath", "");
            try { task.status = TaskStatus.valueOf(json.optString("status", "FAILED")); }
            catch (Throwable ignored) { task.status = TaskStatus.FAILED; }
            task.downloadedBytes = json.optLong("downloadedBytes", 0);
            task.totalBytes = json.optLong("totalBytes", -1);
            task.estimatedTotalBytes = json.optLong("estimatedTotalBytes", -1);
            task.progressPercent = json.optInt("progressPercent", -1);
            if (task.progressPercent < -1) task.progressPercent = -1;
            task.errorMessage = json.optString("errorMessage", "");
            task.updatedAt = json.optLong("updatedAt", 0);
            org.json.JSONObject headerJson = json.optJSONObject("headers");
            if (headerJson != null) {
                java.util.Iterator<String> keys = headerJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    task.headers.put(key, headerJson.optString(key, ""));
                }
            }
            return task;
        }
    }

    private static final class ProgressState {
        long lastFlushAt;
        long lastFlushedBytes;
        long lastTotalBytes = -1;
        long lastSampleAtNanos;
        long lastSampleBytes;
        final ArrayDeque<SpeedSample> samples = new ArrayDeque<>();

        ProgressState(long lastFlushAt, long lastSampleAtNanos, long lastFlushedBytes) {
            this.lastFlushAt = lastFlushAt;
            this.lastFlushedBytes = lastFlushedBytes;
            this.lastSampleAtNanos = lastSampleAtNanos;
            this.lastSampleBytes = lastFlushedBytes;
            this.samples.addLast(new SpeedSample(lastSampleAtNanos, lastFlushedBytes));
        }
    }

    private static final class SpeedSample {
        final long atNanos;
        final long bytes;

        SpeedSample(long atNanos, long bytes) {
            this.atNanos = atNanos;
            this.bytes = bytes;
        }
    }

    public static final class DownloadHandle {
        private volatile boolean cancelled;
        private volatile boolean pauseRequested;
        private volatile boolean workerStarted;
        private volatile Call call;
        private volatile Future<?> future;
        private volatile File completedFile;
        private volatile Runnable cancelAction;
        private final Object lifecycleLock = new Object();
        private final CopyOnWriteArraySet<Call> activeCalls = new CopyOnWriteArraySet<>();
        private final CountDownLatch stopped = new CountDownLatch(1);

        private static DownloadHandle noop() {
            DownloadHandle handle = new DownloadHandle();
            handle.cancelled = true;
            return handle;
        }

        private static DownloadHandle completed(File file) {
            DownloadHandle handle = new DownloadHandle();
            handle.completedFile = file;
            return handle;
        }

        public void cancel() {
            pauseRequested = false;
            cancelled = true;
            stopActiveWork();
        }

        void pause() {
            pauseRequested = true;
            cancelled = true;
            stopActiveWork();
        }

        private void stopActiveWork() {
            Call activeCall = call;
            if (activeCall != null) activeCall.cancel();
            for (Call request : activeCalls) {
                try { request.cancel(); } catch (Throwable ignored) { }
            }
            synchronized (lifecycleLock) {
                Future<?> activeFuture = future;
                if (activeFuture != null) {
                    activeFuture.cancel(true);
                    // 如果工作体尚未开始，它不会进入 finally 释放停止闩锁。
                    // markStarted() 与此检查共用锁，避免启动/取消竞态。
                    if (!workerStarted) stopped.countDown();
                }
            }
            Runnable action = cancelAction;
            if (action != null) action.run();
        }

        boolean isPauseRequested() {
            return pauseRequested;
        }

        void setFuture(@Nullable Future<?> value) {
            synchronized (lifecycleLock) {
                future = value;
                if (cancelled && value != null) {
                    value.cancel(true);
                    if (!workerStarted) stopped.countDown();
                }
            }
        }

        boolean markStarted() {
            synchronized (lifecycleLock) {
                workerStarted = true;
                return !cancelled;
            }
        }

        void awaitStopped() {
            boolean interrupted = false;
            synchronized (lifecycleLock) {
                // A registered handle can be paused before its executor Future is attached.
                // It has no worker or file owner yet; setFuture() will cancel the later submit.
                if (!workerStarted && future == null) stopped.countDown();
            }
            while (true) {
                try {
                    stopped.await();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        void markStopped() {
            stopped.countDown();
        }

        void setCancelAction(@Nullable Runnable action) {
            cancelAction = action;
            if (cancelled && action != null) action.run();
        }

        void registerCall(@Nullable Call request) {
            if (request == null) return;
            activeCalls.add(request);
            if (cancelled) request.cancel();
        }

        void unregisterCall(@Nullable Call request) {
            if (request != null) activeCalls.remove(request);
        }

        void setCompletedFile(@Nullable File file) {
            completedFile = file;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        @Nullable
        public File completedFile() {
            return completedFile;
        }
    }
}
