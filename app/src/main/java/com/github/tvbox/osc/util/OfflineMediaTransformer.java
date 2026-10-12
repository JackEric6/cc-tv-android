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

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

/**
 * 将可识别的非 MP4 点播直链统一转换为 MP4。
 *
 * <p>该类只负责有限点播媒体；是否为直播由上游地址类型和播放器链路决定。
 * Transformer 失败时删除临时文件，不把原格式伪装成 MP4 缓存。
 */
final class OfflineMediaTransformer {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            OfflineVideoCache.MAX_ACTIVE_DOWNLOADS, runnable -> {
        Thread thread = new Thread(runnable, "cc-tv-offline-media");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<String, OfflineVideoCache.DownloadHandle> ACTIVE =
            new ConcurrentHashMap<>();

    private OfflineMediaTransformer() {
    }

    /** 只接受明确的媒体扩展名，避免把解析页、JSON 和 HTML 送进转换器。 */
    static boolean isConvertibleMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim();
        if (!(value.startsWith("http://") || value.startsWith("https://"))) return false;
        try {
            HttpUrl parsed = HttpUrl.get(value);
            String path = parsed.encodedPath().toLowerCase(Locale.ROOT);
            String query = parsed.query() == null ? "" : parsed.query().toLowerCase(Locale.ROOT);
            return path.endsWith(".mpd") || path.endsWith(".ts") || path.endsWith(".flv")
                    || path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".mov")
                    || path.endsWith(".m4v") || path.endsWith(".avi") || path.endsWith(".wmv")
                    || path.endsWith(".mpg") || path.endsWith(".mpeg")
                    || path.endsWith(".3gp") || path.endsWith(".ogv")
                    || query.contains("type=mpd") || query.contains("type=ts")
                    || query.contains("type=flv");
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
        if (context == null || !isConvertibleMediaUrl(normalizedRemoteUrl)) {
            noop.cancel();
            return noop;
        }
        String key = OfflineVideoCache.buildCacheKey(sourceKey, movieId, playFlag,
                episodeIndex, normalizedRemoteUrl);
        boolean pausedTask = OfflineVideoCache.isPausedTask(context, key);
        File target = OfflineVideoCache.targetFile(context, key);
        // 仅凭磁盘上存在目标文件不能判定转换已完成：暂停、进程中断或旧版本
        // 都可能留下半成品。必须同时满足任务索引为 COMPLETED 且文件是有效最终文件。
        if (!pausedTask
                && OfflineVideoCache.isCompletedTask(context, key)
                && OfflineVideoCache.isFinalCacheFile(target)) {
            OfflineVideoCache.registerTransformerTask(context, key, sourceKey, movieId, playFlag,
                    episodeIndex, normalizedRemoteUrl, headers, target, null);
            OfflineVideoCache.markCompleted(context, key, target);
            return completed(target);
        }
        OfflineVideoCache.DownloadHandle existing = ACTIVE.get(key);
        if (existing != null) {
            if (!existing.isCancelled()) return existing;
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

    private static void convert(Context context, String key, File target, String remoteUrl,
                                Map<String, String> headers,
                                OfflineVideoCache.DownloadHandle handle) {
        HandlerThread transformerThread = new HandlerThread("cc-tv-media-transformer");
        File outputPart = new File(target.getParentFile(), target.getName() + ".part");
        if (!OfflineVideoCache.acquireDownloadSlot(handle)) {
            ACTIVE.remove(key, handle);
            OfflineVideoCache.unregisterActive(key, handle);
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean slotReleased =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable releaseSlot = () -> {
            if (slotReleased.compareAndSet(false, true)) OfflineVideoCache.releaseDownloadSlot();
        };
        try {
            File parent = target.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                ACTIVE.remove(key, handle);
                OfflineVideoCache.unregisterActive(key, handle);
                OfflineVideoCache.markFailed(context, key, "创建缓存目录失败", handle);
                releaseSlot.run();
                return;
            }
            if (outputPart.exists()) outputPart.delete();

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
                            LOG.i("offline-media-transform-error key=" + key + " code="
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
                if (!handle.isPauseRequested()) {
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                }
                thread.quitSafely();
                releaseSlot.run();
            });
            progressPoller[0] = new OfflineTransformerProgress.Poller(
                    transformerHandler, transformer, context, key, outputPart, handle);

            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(remoteUrl));
            String mimeType = mimeTypeFor(remoteUrl);
            if (!TextUtils.isEmpty(mimeType)) item.setMimeType(mimeType);
            transformerHandler.post(() -> {
                if (handle.isCancelled()) {
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                    releaseSlot.run();
                    return;
                }
                try {
                    transformer.start(item.build(), outputPart.getAbsolutePath());
                    progressPoller[0].start();
                } catch (Throwable startError) {
                    progressPoller[0].stop();
                    outputPart.delete();
                    ACTIVE.remove(key, handle);
                    OfflineVideoCache.unregisterActive(key, handle);
                    OfflineVideoCache.markFailed(context, key, "转换启动失败", handle);
                    releaseSlot.run();
                    LOG.i("offline-media-transform-start-failed key=" + key + " error="
                            + startError.getMessage());
                    thread.quitSafely();
                }
            });
        } catch (Throwable th) {
            outputPart.delete();
            ACTIVE.remove(key, handle);
            OfflineVideoCache.unregisterActive(key, handle);
            LOG.i("offline-media-transform-failed key=" + key + " error=" + th.getMessage());
            if (!handle.isCancelled()) OfflineVideoCache.markFailed(context, key, "媒体转换失败", handle);
            releaseSlot.run();
            transformerThread.quitSafely();
        }
    }

    @Nullable
    private static String mimeTypeFor(String url) {
        try {
            HttpUrl parsed = HttpUrl.get(url);
            String path = parsed.encodedPath().toLowerCase(Locale.ROOT);
            String query = parsed.query() == null ? "" : parsed.query().toLowerCase(Locale.ROOT);
            if (path.endsWith(".mpd") || query.contains("type=mpd")) return MimeTypes.APPLICATION_MPD;
            if (path.endsWith(".ts") || query.contains("type=ts")) return MimeTypes.VIDEO_MP2T;
            if (path.endsWith(".webm")) return MimeTypes.VIDEO_WEBM;
            if (path.endsWith(".ogv")) return MimeTypes.VIDEO_OGG;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
