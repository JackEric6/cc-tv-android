package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.ExoPlayerAssetLoader;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.github.catvod.net.OkHttp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 有限 HLS VOD 离线缓存适配器。
 *
 * <p>无加密 MPEG-TS VOD 优先并发下载分片并按原顺序合并，避免转码带来的失败和速度损失；
 * AES-128、BYTERANGE、fMP4 等复杂 HLS 继续交给 Media3 Transformer。只有未结束直播、EVENT
 * 和低延迟实时流不进入离线文件任务。
 */
final class OfflineHlsTransformer {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            OfflineVideoCache.MAX_ACTIVE_DOWNLOADS, runnable -> {
        Thread thread = new Thread(runnable, "avbox-offline-hls");
        thread.setDaemon(true);
        return thread;
    });
    // 单个 HLS 任务参考 iOS 实现保持 6 路动态并行；任务总数仍由共享的 5 个下载名额限制。
    private static final int DIRECT_SEGMENT_CONCURRENCY = 6;
    // CDN 偶发 502/超时不能让整集在几百个分片后直接失败；沿用 iOS 的
    // 分片级续传模型，但把瞬时错误交给带退避的有限重试。
    private static final int DIRECT_SEGMENT_RETRIES = 6;
    private static final long DIRECT_SEGMENT_RETRY_BASE_MS = 400L;
    private static final long DIRECT_SEGMENT_RETRY_MAX_MS = 5_000L;
    private static final long MAX_DIRECT_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final ConcurrentHashMap<String, OfflineVideoCache.DownloadHandle> ACTIVE =
            new ConcurrentHashMap<>();

    private OfflineHlsTransformer() {
    }

    static boolean isHlsVodCandidate(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            HttpUrl parsed = HttpUrl.get(url.trim());
            return parsed.encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
        } catch (Throwable ignored) {
            return false;
        }
    }

    static OfflineVideoCache.DownloadHandle prefetch(Context context, String sourceKey,
                                                      String movieId, String playFlag,
                                                      int episodeIndex, String remoteUrl,
                                                      @Nullable Map<String, String> headers) {
        OfflineVideoCache.DownloadHandle noop = new OfflineVideoCache.DownloadHandle();
        final String normalizedRemoteUrl = OfflineVideoCache.normalizeRemoteUrl(remoteUrl);
        if (context == null || !isHlsVodCandidate(normalizedRemoteUrl)) {
            noop.cancel();
            return noop;
        }
        String key = OfflineVideoCache.buildCacheKey(sourceKey, movieId, playFlag, episodeIndex,
                normalizedRemoteUrl);
        boolean pausedTask = OfflineVideoCache.isPausedTask(context, key);
        boolean completedTask = OfflineVideoCache.isCompletedTask(context, key);
        File directTarget = OfflineVideoCache.targetFileForExtension(context, key, ".ts");
        if (!pausedTask && completedTask && OfflineVideoCache.isFinalCacheFile(directTarget)) {
            OfflineVideoCache.registerTransformerTask(context, key, sourceKey, movieId, playFlag,
                    episodeIndex, normalizedRemoteUrl, headers, directTarget, null);
            OfflineVideoCache.markCompleted(context, key, directTarget);
            return completed(directTarget);
        }
        File target = OfflineVideoCache.targetFile(context, key);
        if (!pausedTask && completedTask && OfflineVideoCache.isFinalCacheFile(target)) {
            OfflineVideoCache.registerTransformerTask(context, key, sourceKey, movieId, playFlag,
                    episodeIndex, normalizedRemoteUrl, headers, target, null);
            OfflineVideoCache.markCompleted(context, key, target);
            return completed(target);
        }
        OfflineVideoCache.DownloadHandle existing = ACTIVE.get(key);
        if (existing != null) {
            if (!existing.isCancelled()) return existing;
            // 暂停后旧任务可能还在 finally 中收尾。先等它退出，避免新一轮
            // 与旧一轮同时操作同名分片临时文件。
            existing.awaitStopped();
            ACTIVE.remove(key, existing);
        }
        OfflineVideoCache.DownloadHandle handle = new OfflineVideoCache.DownloadHandle();
        OfflineVideoCache.DownloadHandle raced = ACTIVE.putIfAbsent(key, handle);
        if (raced != null) return raced;
        Map<String, String> safeHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        OfflineVideoCache.registerTransformerTask(context, key, sourceKey, movieId, playFlag,
                episodeIndex, normalizedRemoteUrl, safeHeaders, target, handle);
        handle.setFuture(EXECUTOR.submit(() -> {
            if (!handle.markStarted()) {
                handle.markStopped();
                return;
            }
            try {
                convert(context, key, target, normalizedRemoteUrl, safeHeaders, handle);
            } finally {
                handle.markStopped();
            }
        }));
        return handle;
    }

    private static OfflineVideoCache.DownloadHandle completed(File target) {
        OfflineVideoCache.DownloadHandle handle = new OfflineVideoCache.DownloadHandle();
        handle.setCompletedFile(target);
        return handle;
    }

    private static void convert(Context context, String key, File target, String playlistUrl,
                                Map<String, String> headers,
                                OfflineVideoCache.DownloadHandle handle) {
        HandlerThread transformerThread = new HandlerThread("avbox-hls-transformer");
        File outputPart = new File(target.getParentFile(), target.getName() + ".part");
        if (!OfflineVideoCache.acquireDownloadSlot(handle)) {
            ACTIVE.remove(key, handle);
            OfflineVideoCache.unregisterActive(key, handle);
            return;
        }
        AtomicBoolean slotReleased = new AtomicBoolean(false);
        Runnable releaseSlot = () -> {
            if (slotReleased.compareAndSet(false, true)) OfflineVideoCache.releaseDownloadSlot();
        };
        try {
            String playlist = getText(playlistUrl, headers);
            if (TextUtils.isEmpty(playlist) || handle.isCancelled()) {
                if (!handle.isPauseRequested()) {
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                }
                if (handle.isCancelled()) OfflineVideoCache.markCancelled(context, key, handle);
                else OfflineVideoCache.markFailed(context, key, "读取播放列表失败", handle);
                releaseSlot.run();
                return;
            }
            String selectedUrl = selectVariant(playlistUrl, playlist);
            if (!TextUtils.equals(selectedUrl, playlistUrl)) {
                playlist = getText(selectedUrl, headers);
                playlistUrl = selectedUrl;
            }
            if (!isFiniteVodPlaylist(playlist)) {
                ACTIVE.remove(key, handle);
                OfflineVideoCache.unregisterActive(key, handle);
                OfflineVideoCache.markFailed(context, key, "不是有限点播流", handle);
                releaseSlot.run();
                return;
            }
            List<MediaSegment> directSegments = parseDirectTsSegments(playlistUrl, playlist);
            if (directSegments != null && !directSegments.isEmpty()) {
                File directTarget = OfflineVideoCache.targetFileForExtension(context, key, ".ts");
                OfflineVideoCache.updateTaskTarget(context, key, directTarget);
                downloadDirectSegments(context, key, directTarget, directSegments, headers, handle);
                releaseSlot.run();
                return;
            }
            File parent = target.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                ACTIVE.remove(key, handle);
                OfflineVideoCache.unregisterActive(key, handle);
                OfflineVideoCache.markFailed(context, key, "创建缓存目录失败", handle);
                releaseSlot.run();
                return;
            }
            if (outputPart.exists()) outputPart.delete();

            // 通过 ExoPlayer 的 HLS MediaSource 直接读取远程播放列表和分片，保留 AES-128、
            // BYTERANGE、fMP4、变体流等标准 HLS 能力，不再把这些合法点播格式提前跳过。
            final OfflineTransformerProgress.Counter progressCounter =
                    new OfflineTransformerProgress.Counter();
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers);
            DataSource.Factory dataSourceFactory = OfflineTransformerProgress.countingFactory(
                    new DefaultDataSource.Factory(context, httpFactory), context, key,
                    progressCounter, handle);
            MediaSource.Factory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
            ExoPlayerAssetLoader.Factory assetLoaderFactory = new ExoPlayerAssetLoader.Factory(
                    context,
                    new DefaultDecoderFactory.Builder(context).build(),
                    Clock.DEFAULT,
                    mediaSourceFactory);

            transformerThread.start();
            OfflineVideoCache.markDownloading(context, key, -1, handle);
            final HandlerThread thread = transformerThread;
            final Handler transformerHandler = new Handler(thread.getLooper());
            final OfflineTransformerProgress.Poller[] progressPoller =
                    new OfflineTransformerProgress.Poller[1];
            Transformer transformer = new Transformer.Builder(context)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setLooper(thread.getLooper())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(androidx.media3.transformer.Composition composition,
                                                ExportResult result) {
                            if (progressPoller[0] != null) progressPoller[0].stop();
                            if (!handle.isCancelled()
                                    && OfflineVideoCache.commitPart(outputPart, target, key, handle)) {
                                handle.setCompletedFile(target);
                                OfflineVideoCache.markCompleted(context, key, target, handle);
                            } else {
                                if (OfflineVideoCache.isCurrentHandle(key, handle)) outputPart.delete();
                                if (handle.isCancelled()) OfflineVideoCache.markCancelled(context, key, handle);
                                else OfflineVideoCache.markFailed(context, key, "转换未完成", handle);
                            }
                            ACTIVE.remove(key, handle);
                            OfflineVideoCache.unregisterActive(key, handle);
                            releaseSlot.run();
                            thread.quitSafely();
                        }

                        @Override
                        public void onError(androidx.media3.transformer.Composition composition,
                                            ExportResult result, ExportException exception) {
                            if (progressPoller[0] != null) progressPoller[0].stop();
                            if (OfflineVideoCache.isCurrentHandle(key, handle)) outputPart.delete();
                            ACTIVE.remove(key, handle);
                            LOG.i("offline-hls-transform-error key=" + key + " code="
                                    + exception.getErrorCodeName() + " message=" + exception.getMessage());
                            if (handle.isCancelled()) {
                                OfflineVideoCache.markCancelled(context, key, handle);
                            } else {
                                OfflineVideoCache.markFailed(context, key,
                                        exception.getMessage() == null ? "媒体转换失败" : exception.getMessage(), handle);
                            }
                            releaseSlot.run();
                            thread.quitSafely();
                        }
                    })
                    .build();
            handle.setCancelAction(() -> {
                try { transformer.cancel(); } catch (Throwable ignored) { }
                if (progressPoller[0] != null) progressPoller[0].stop();
                outputPart.delete();
                thread.quitSafely();
                if (!handle.isPauseRequested()) {
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                }
                releaseSlot.run();
            });
            progressPoller[0] = new OfflineTransformerProgress.Poller(
                    transformerHandler, transformer, context, key, handle);
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(playlistUrl))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build();
            // Transformer 的 start/cancel 必须在它绑定的 application Looper 上调用。
            transformerHandler.post(() -> {
                if (handle.isCancelled()) {
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                    releaseSlot.run();
                    return;
                }
                try {
                    transformer.start(item, outputPart.getAbsolutePath());
                    progressPoller[0].start();
                } catch (Throwable startError) {
                    progressPoller[0].stop();
                    outputPart.delete();
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                    OfflineVideoCache.markFailed(context, key, "转换启动失败", handle);
                    releaseSlot.run();
                    LOG.i("offline-hls-transform-start-failed key=" + key + " error="
                            + startError.getMessage());
                    thread.quitSafely();
                }
            });
        } catch (Throwable th) {
            LOG.i("offline-hls-transform-failed key=" + key + " error=" + th.getMessage());
            outputPart.delete();
            ACTIVE.remove(key, handle);
            OfflineVideoCache.unregisterActive(key, handle);
            if (!handle.isCancelled()) OfflineVideoCache.markFailed(context, key, "读取播放列表失败", handle);
            releaseSlot.run();
            transformerThread.quitSafely();
        }
    }

    /** 返回可直接拼接的无加密 MPEG-TS 分片；复杂 HLS 返回 null，交给 Transformer。 */
    static List<MediaSegment> parseDirectTsSegments(String playlistUrl, String playlist) {
        if (TextUtils.isEmpty(playlist) || playlist.contains("#EXT-X-STREAM-INF")) return null;
        String upper = playlist.toUpperCase(Locale.ROOT);
        if (upper.contains("#EXT-X-MAP:") || upper.contains("#EXT-X-BYTERANGE:")) return null;
        int keyStart = upper.indexOf("#EXT-X-KEY:");
        while (keyStart >= 0) {
            int end = upper.indexOf('\n', keyStart);
            String keyLine = end < 0 ? upper.substring(keyStart) : upper.substring(keyStart, end);
            if (!keyLine.contains("METHOD=NONE")) return null;
            keyStart = upper.indexOf("#EXT-X-KEY:", end < 0 ? upper.length() : end + 1);
        }
        String[] lines = playlist.split("\\r?\\n");
        List<MediaSegment> segments = new ArrayList<>();
        boolean expectUri = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                expectUri = true;
                continue;
            }
            if (line.startsWith("#")) continue;
            if (!expectUri) continue;
            String resolved = resolve(playlistUrl, line);
            if (TextUtils.isEmpty(resolved)) return null;
            String path = resolved.toLowerCase(Locale.ROOT);
            if (path.contains(".m4s") || path.contains(".mp4") || path.contains(".aac")
                    || path.contains(".webvtt")) return null;
            segments.add(new MediaSegment(segments.size(), resolved));
            expectUri = false;
        }
        return segments.isEmpty() ? null : segments;
    }

    static boolean isDirectTsPlaylist(String playlistUrl, String playlist) {
        List<MediaSegment> segments = parseDirectTsSegments(playlistUrl, playlist);
        return segments != null && !segments.isEmpty();
    }

    private static void downloadDirectSegments(Context context, String key, File target,
                                               List<MediaSegment> segments,
                                               Map<String, String> headers,
                                               OfflineVideoCache.DownloadHandle handle) {
        File parent = target.getParentFile();
        File outputPart = new File(parent, target.getName() + ".part");
        List<File> segmentFiles = new CopyOnWriteArrayList<>();
        List<Future<File>> futures = new CopyOnWriteArrayList<>();
        List<okhttp3.Call> calls = new CopyOnWriteArrayList<>();
        // 分片线程池必须按任务隔离。共享池会被一个长剧集的前 N 个分片占满，
        // 让其他下载任务看起来像“只有一个在下载”。
        final ExecutorService segmentExecutor = Executors.newFixedThreadPool(
                Math.min(DIRECT_SEGMENT_CONCURRENCY, Math.max(1, segments.size())), runnable -> {
                    Thread thread = new Thread(runnable, "avbox-offline-hls-segment");
                    thread.setDaemon(true);
                    return thread;
                });
        // 以实际完成顺序消费分片结果，失败可以立即停止剩余任务；这与 iOS
        // withThrowingTaskGroup 的动态补充策略一致，避免按提交顺序等待慢分片。
        CompletionService<File> completionService = new ExecutorCompletionService<>(segmentExecutor);
        AtomicLong downloaded = new AtomicLong(0L);
        AtomicLong total = new AtomicLong(0L);
        AtomicInteger completedSegments = new AtomicInteger(0);
        ConcurrentHashMap<Integer, String> failureReasons = new ConcurrentHashMap<>();
        // 每个分片只保留已观测最大值，失败重试不会把总体进度减回去。
        AtomicLongArray segmentDownloaded = new AtomicLongArray(segments.size());
        AtomicLongArray segmentExpected = new AtomicLongArray(segments.size());
        for (int i = 0; i < segments.size(); i++) segmentExpected.set(i, -1L);
        handle.setCancelAction(() -> {
            for (okhttp3.Call call : calls) call.cancel();
            for (Future<File> future : futures) future.cancel(true);
            // 暂停必须保留正在写入的 .part，下一轮会按分片偏移发送 Range；
            // 取消/删除任务才清理临时文件，避免把可恢复数据误删。
            if (!handle.isPauseRequested()) deleteSegmentTemps(segmentFiles);
            if (outputPart.exists()) outputPart.delete();
            if (!handle.isPauseRequested()) deleteFiles(segmentFiles);
            if (!handle.isPauseRequested()) {
                ACTIVE.remove(key, handle);
                OfflineVideoCache.unregisterActive(key, handle);
            }
        });
        try {
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                OfflineVideoCache.markFailed(context, key, "创建缓存目录失败", handle);
                return;
            }
            if (outputPart.exists()) outputPart.delete();
            int reusableSegments = 0;
            List<Integer> pendingIndices = new ArrayList<>();
            for (MediaSegment segment : segments) {
                // 完成态分片使用不带 .part 的名字；写入期间始终落到独立临时文件，
                // 这样进程被杀或暂停时不会留下“看起来完整”的半个分片。
                File segmentFile = new File(parent, target.getName() + ".seg-" + segment.index);
                segmentFiles.add(segmentFile);
                if (isReusableSegment(segmentFile)) {
                    long size = segmentFile.length();
                    segmentDownloaded.set(segment.index, size);
                    segmentExpected.set(segment.index, size);
                    downloaded.addAndGet(size);
                    total.addAndGet(size);
                    reusableSegments++;
                } else {
                    File temporary = segmentTemporary(segmentFile);
                    long partial = temporary.isFile() ? Math.max(0L, temporary.length()) : 0L;
                    if (partial > 0L) {
                        segmentDownloaded.set(segment.index, partial);
                        downloaded.addAndGet(partial);
                    }
                    pendingIndices.add(segment.index);
                }
            }

            // 先扫描并统计可复用分片，再进入 DOWNLOADING；续传不能把已有进度清零。
            OfflineVideoCache.markDownloadingForResume(context, key, -1, handle);

            // 先报告可复用分片，再按 iOS 的动态任务组策略维持固定数量的在飞请求：
            // 一个完成后才补下一个，避免一次性创建整部剧集的连接。
            if (reusableSegments > 0) {
                completedSegments.addAndGet(reusableSegments);
                OfflineVideoCache.markSegmentProgress(context, key, completedSegments.get(),
                        segments.size(), downloaded.get(), handle);
            } else if (downloaded.get() > 0L) {
                OfflineVideoCache.markProgress(context, key, downloaded.get(), -1L,
                        false, handle);
            }
            int nextPending = 0;
            int inFlight = Math.min(DIRECT_SEGMENT_CONCURRENCY, pendingIndices.size());
            for (int i = 0; i < inFlight; i++) {
                final int segmentIndex = pendingIndices.get(nextPending++);
                    final MediaSegment segment = segments.get(segmentIndex);
                    final File segmentFile = segmentFiles.get(segmentIndex);
                    futures.add(completionService.submit(() -> {
                        File result = downloadSegment(context, key, segment, segmentFile, headers,
                            handle, downloaded, total, segmentDownloaded, segmentExpected, calls,
                            failureReasons);
                    if (result != null && result.isFile() && result.length() > 0) {
                        int completed = completedSegments.incrementAndGet();
                        OfflineVideoCache.markSegmentProgress(context, key, completed,
                                segments.size(), downloaded.get(), handle);
                    }
                    return result;
                }));
            }
            int completedFutures = 0;
            while (completedFutures < pendingIndices.size()) {
                File result = completionService.take().get();
                if (result == null || !result.isFile() || result.length() <= 0) {
                    for (okhttp3.Call call : calls) call.cancel();
                    for (Future<File> pending : futures) pending.cancel(true);
                    if (!handle.isCancelled()) {
                        String reason = "网络或 CDN 临时错误";
                        for (Map.Entry<Integer, String> entry : failureReasons.entrySet()) {
                            if (!TextUtils.isEmpty(entry.getValue())) {
                                reason = "分片 " + entry.getKey() + ": " + entry.getValue();
                                break;
                            }
                        }
                        OfflineVideoCache.markFailed(context, key,
                                "分片下载失败（已保留已完成分片）: " + reason, handle);
                    }
                    return;
                }
                completedFutures++;
                if (nextPending < pendingIndices.size()) {
                    final int segmentIndex = pendingIndices.get(nextPending++);
                    final MediaSegment segment = segments.get(segmentIndex);
                    final File segmentFile = segmentFiles.get(segmentIndex);
                    futures.add(completionService.submit(() -> {
                        File nextResult = downloadSegment(context, key, segment, segmentFile, headers,
                                handle, downloaded, total, segmentDownloaded, segmentExpected, calls,
                                failureReasons);
                        if (nextResult != null && nextResult.isFile() && nextResult.length() > 0) {
                            int completed = completedSegments.incrementAndGet();
                            OfflineVideoCache.markSegmentProgress(context, key, completed,
                                    segments.size(), downloaded.get(), handle);
                        }
                        return nextResult;
                    }));
                }
            }
            if (handle.isCancelled()) {
                OfflineVideoCache.markCancelled(context, key, handle);
                return;
            }
            long mergedBytes = 0L;
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(outputPart))) {
                byte[] buffer = new byte[256 * 1024];
                for (File segmentFile : segmentFiles) {
                    try (InputStream input = new BufferedInputStream(new FileInputStream(segmentFile))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (handle.isCancelled()) {
                                OfflineVideoCache.markCancelled(context, key, handle);
                                return;
                            }
                            mergedBytes += read;
                            if (mergedBytes > MAX_DIRECT_BYTES) {
                                OfflineVideoCache.markFailed(context, key, "缓存文件超过大小限制", handle);
                                return;
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                output.flush();
            }
            if (!outputPart.isFile() || outputPart.length() <= 0
                    || !OfflineVideoCache.commitPart(outputPart, target, key, handle)) {
                OfflineVideoCache.markFailed(context, key, "保存缓存文件失败", handle);
                return;
            }
            if (handle.isCancelled()) {
                if (target.exists()) target.delete();
                OfflineVideoCache.markCancelled(context, key, handle);
                return;
            }
            LOG.i("offline-hls-merge-complete key=" + key + " bytes=" + mergedBytes);
            OfflineVideoCache.markProgress(context, key, mergedBytes, mergedBytes, true, handle);
            handle.setCompletedFile(target);
            OfflineVideoCache.markCompleted(context, key, target, handle);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (handle.isCancelled()) OfflineVideoCache.markCancelled(context, key, handle);
            else OfflineVideoCache.markFailed(context, key, "下载被中断", handle);
        } catch (Throwable error) {
            LOG.i("offline-hls-direct-failed key=" + key + " error=" + error.getMessage());
            if (handle.isCancelled()) OfflineVideoCache.markCancelled(context, key, handle);
            else OfflineVideoCache.markFailed(context, key,
                    TextUtils.isEmpty(error.getMessage()) ? "分片下载失败" : error.getMessage(), handle);
        } finally {
            // 合并提交成功后再清理分片；暂停、网络失败或进程中断时保留完整分片和
            // .part，下一次任务会按分片偏移续传。显式取消/删除由 cancelAction 清理。
            // 旧一轮暂停后可能已有新句柄复用同一组分片文件，不能清理新一轮产物。
            if (OfflineVideoCache.isCurrentHandle(key, handle)) {
                if (target.isFile() && target.length() > 0) deleteFiles(segmentFiles);
                if (outputPart.exists()) outputPart.delete();
            }
            ACTIVE.remove(key, handle);
            OfflineVideoCache.unregisterActive(key, handle);
            OfflineVideoCache.shutdownAndAwait(segmentExecutor);
        }
    }

    private static File downloadSegment(Context context, String key, MediaSegment segment,
                                        File output, Map<String, String> headers,
                                        OfflineVideoCache.DownloadHandle handle,
                                        AtomicLong downloaded, AtomicLong total,
                                        AtomicLongArray segmentDownloaded,
                                        AtomicLongArray segmentExpected,
                                        List<okhttp3.Call> calls,
                                        ConcurrentHashMap<Integer, String> failureReasons) {
        String lastError = "";
        for (int attempt = 0; attempt < DIRECT_SEGMENT_RETRIES; attempt++) {
            if (handle.isCancelled()) return null;
            okhttp3.Call call = null;
            File temporary = segmentTemporary(output);
            try {
                long localOffset = temporary.isFile() ? Math.max(0L, temporary.length()) : 0L;
                long reportedOffset = segmentDownloaded.get(segment.index);
                if (reportedOffset < localOffset
                        && segmentDownloaded.compareAndSet(segment.index, reportedOffset, localOffset)) {
                    downloaded.addAndGet(localOffset - reportedOffset);
                }
                Request.Builder builder = new Request.Builder().url(segment.url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                        try { builder.addHeader(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
                    }
                }
                builder.header("Accept-Encoding", "identity");
                if (localOffset > 0L) builder.header("Range", "bytes=" + localOffset + "-");
                call = OfflineVideoCache.downloadClient().newCall(builder.build());
                calls.add(call);
                try (Response response = call.execute()) {
                    if (localOffset > 0L && response.code() == 416) {
                        long rangeTotal = OfflineVideoCache.contentRangeTotal(response);
                        if (rangeTotal > 0L && rangeTotal == localOffset
                                && temporary.isFile() && temporary.length() == rangeTotal) {
                            if (output.exists() && !output.delete()) {
                                throw new IOException("无法替换分片文件");
                            }
                            if (!temporary.renameTo(output)) throw new IOException("保存分片失败");
                            segmentDownloaded.set(segment.index, rangeTotal);
                            return output;
                        }
                        if (temporary.exists()) temporary.delete();
                        segmentDownloaded.set(segment.index, 0L);
                        downloaded.addAndGet(-localOffset);
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("HTTP " + response.code());
                    }
                    boolean append = localOffset > 0L
                            && OfflineVideoCache.acceptsRange(response, localOffset);
                    if (localOffset > 0L && !append) {
                        // CDN 忽略 Range 时，200 响应是完整分片；若返回了错误起点的
                        // 206，则不能把未知偏移的响应当作完整分片，改发一次无 Range 请求。
                        if (temporary.exists() && !temporary.delete()) {
                            throw new IOException("无法重置分片临时文件");
                        }
                        downloaded.addAndGet(-localOffset);
                        segmentDownloaded.set(segment.index, 0L);
                        if (response.code() == 206) continue;
                        localOffset = 0L;
                    }
                    long length = response.body().contentLength();
                    long expectedLength = append
                            ? OfflineVideoCache.contentRangeTotal(response) : length;
                    if (expectedLength <= 0L && append && length > 0L) {
                        expectedLength = localOffset + length;
                    }
                    if (expectedLength > 0) {
                        // 同一分片重试时只接受更大的可验证长度，绝不回退总量。
                        while (true) {
                            long oldExpected = segmentExpected.get(segment.index);
                            if (expectedLength <= oldExpected) break;
                            long previousExpected = Math.max(0L, oldExpected);
                            if (segmentExpected.compareAndSet(segment.index, oldExpected, expectedLength)) {
                                total.addAndGet(expectedLength - previousExpected);
                                break;
                            }
                        }
                    }
                    long localBytes = localOffset;
                    boolean first = true;
                    try (InputStream input = new BufferedInputStream(response.body().byteStream());
                         OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(temporary, append))) {
                        byte[] buffer = new byte[256 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (handle.isCancelled()) return null;
                            if (first) {
                                first = false;
                                if (looksLikeTextError(buffer, read)) throw new IOException("服务器返回了错误页面");
                            }
                            localBytes += read;
                            long oldReported = segmentDownloaded.get(segment.index);
                            while (localBytes > oldReported
                                    && !segmentDownloaded.compareAndSet(segment.index,
                                    oldReported, localBytes)) {
                                oldReported = segmentDownloaded.get(segment.index);
                            }
                            if (localBytes > oldReported) downloaded.addAndGet(localBytes - oldReported);
                            if (downloaded.get() > MAX_DIRECT_BYTES) {
                                throw new IOException("缓存文件超过大小限制");
                            }
                            outputStream.write(buffer, 0, read);
                            long knownTotal = total.get();
                            // 只有所有分片都返回可信 Content-Length 时才把总量交给
                            // 进度层；否则只显示已下载字节和分片阶段进度。
                            boolean completeTotal = knownTotal > 0
                                    && allSegmentLengthsKnown(segmentExpected);
                            OfflineVideoCache.markProgress(context, key, downloaded.get(),
                                    knownTotal > 0 ? knownTotal : -1, completeTotal, handle);
                        }
                        outputStream.flush();
                    }
                    if (localBytes <= 0 || !temporary.isFile() || temporary.length() <= 0) {
                        throw new IOException("空分片");
                    }
                    if (handle.isCancelled()) return null;
                    if (output.exists() && !output.delete()) {
                        throw new IOException("无法替换分片文件");
                    }
                    if (!temporary.renameTo(output)) {
                        throw new IOException("保存分片失败");
                    }
                    return output;
                }
            } catch (Throwable error) {
                lastError = TextUtils.isEmpty(error.getMessage())
                        ? error.getClass().getSimpleName() : error.getMessage();
                LOG.i("offline-hls-segment-failed key=" + key
                        + " index=" + segment.index
                        + " attempt=" + (attempt + 1) + "/" + DIRECT_SEGMENT_RETRIES
                        + " error=" + lastError);
                // 暂停和瞬时网络失败都保留 .part；下一次尝试会按实际长度续传。
                if (attempt + 1 >= DIRECT_SEGMENT_RETRIES) {
                    recordSegmentFailure(failureReasons, segment, attempt + 1, lastError);
                    return null;
                }
                if (handle.isCancelled()) return null;
                long backoff = Math.min(DIRECT_SEGMENT_RETRY_MAX_MS,
                        DIRECT_SEGMENT_RETRY_BASE_MS * (1L << Math.min(attempt, 3)));
                try { Thread.sleep(backoff); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (!handle.isCancelled()) {
                        recordSegmentFailure(failureReasons, segment, attempt + 1, "下载线程被中断");
                    }
                    return null;
                }
            } finally {
                if (call != null) calls.remove(call);
            }
        }
        recordSegmentFailure(failureReasons, segment, DIRECT_SEGMENT_RETRIES, lastError);
        return null;
    }

    private static void recordSegmentFailure(ConcurrentHashMap<Integer, String> failureReasons,
                                             MediaSegment segment, int attempts, String error) {
        if (failureReasons == null || segment == null) return;
        String reason = TextUtils.isEmpty(error) ? "未知错误" : error;
        int safeAttempts = Math.max(1, attempts);
        failureReasons.put(segment.index,
                "尝试 " + safeAttempts + "/" + DIRECT_SEGMENT_RETRIES + "，最终错误: " + reason);
    }

    private static boolean allSegmentLengthsKnown(AtomicLongArray lengths) {
        if (lengths == null || lengths.length() == 0) return false;
        for (int i = 0; i < lengths.length(); i++) {
            if (lengths.get(i) <= 0) return false;
        }
        return true;
    }

    /** 只有原子提交后的最终分片才可复用；带 .part 的文件一律视为未完成。 */
    static boolean isReusableSegment(File file) {
        return file != null && file.isFile() && file.length() > 0
                && !file.getName().endsWith(".part");
    }

    private static File segmentTemporary(File file) {
        return new File(file.getParentFile(), file.getName() + ".part");
    }

    /** 删除某个 HLS 任务留下的完整分片和临时分片，供下载页的“删除”操作调用。 */
    static void deleteSegmentArtifacts(File target) {
        if (target == null || target.getParentFile() == null) return;
        String prefix = target.getName() + ".seg-";
        File[] files = target.getParentFile().listFiles((dir, name) ->
                name != null && name.startsWith(prefix));
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.exists()) file.delete();
        }
    }

    private static void deleteSegmentTemps(List<File> segmentFiles) {
        if (segmentFiles == null) return;
        for (File file : segmentFiles) {
            if (file == null) continue;
            File temporary = segmentTemporary(file);
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean looksLikeTextError(byte[] data, int length) {
        if (data == null || length <= 0) return true;
        int probeLength = Math.min(length, 256);
        String probe = new String(data, 0, probeLength, java.nio.charset.StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        return probe.startsWith("<html") || probe.startsWith("<!doctype") || probe.startsWith("#extm3u")
                || probe.startsWith("{\"error");
    }

    private static void deleteFiles(List<File> files) {
        for (File file : files) {
            if (file != null && file.exists()) file.delete();
        }
    }

    static final class MediaSegment {
        final int index;
        final String url;

        MediaSegment(int index, String url) {
            this.index = index;
            this.url = url;
        }
    }

    private static String getText(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                try { builder.addHeader(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
            }
        }
        try (Response response = OfflineVideoCache.downloadClient().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            return response.body().string();
        }
    }

    private static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                try { builder.addHeader(entry.getKey(), entry.getValue()); } catch (Throwable ignored) { }
            }
        }
        try (Response response = OfflineVideoCache.downloadClient().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return response.body().bytes();
        }
    }

    private static String selectVariant(String masterUrl, String playlist) {
        if (!playlist.contains("#EXT-X-STREAM-INF")) return masterUrl;
        String[] lines = playlist.split("\\r?\\n");
        String bestUrl = masterUrl;
        int bestBandwidth = -1;
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String uri = lines[i + 1].trim();
            if (uri.isEmpty() || uri.startsWith("#")) continue;
            int bandwidth = parseAttribute(line, "BANDWIDTH");
            if (bandwidth >= bestBandwidth) {
                bestBandwidth = bandwidth;
                bestUrl = resolve(masterUrl, uri);
            }
        }
        return bestUrl;
    }

    private static int parseAttribute(String line, String name) {
        String marker = name + "=";
        int start = line.indexOf(marker);
        if (start < 0) return -1;
        start += marker.length();
        int end = line.indexOf(',', start);
        String value = end < 0 ? line.substring(start) : line.substring(start, end);
        try { return Integer.parseInt(value.trim()); } catch (Throwable ignored) { return -1; }
    }

    private static String resolve(String base, String child) {
        try {
            HttpUrl resolved = HttpUrl.get(base).resolve(child);
            return resolved == null ? child : resolved.toString();
        } catch (Throwable ignored) {
            return child;
        }
    }

    static boolean isFiniteVodPlaylist(String playlist) {
        if (TextUtils.isEmpty(playlist) || !playlist.contains("#EXTM3U")) return false;
        String upper = playlist.toUpperCase(Locale.ROOT);
        if (!upper.contains("#EXT-X-ENDLIST")) return false;
        if (upper.contains("#EXT-X-PLAYLIST-TYPE:EVENT") || upper.contains("#EXT-X-PLAYLIST-TYPE:LIVE")
                || upper.contains("#EXT-X-PART:")
                || upper.contains("#EXT-X-PRELOAD-HINT") || upper.contains("#EXT-X-SERVER-CONTROL")) {
            return false;
        }
        return true;
    }

    static boolean shouldUseTransformer(String url, String playlist) {
        return isHlsVodCandidate(url) && isFiniteVodPlaylist(playlist);
    }

}
