package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class OfflineVideoCacheTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void acceptsOnlyExplicitMp4DirectLinks() {
        assertTrue(OfflineVideoCache.isExplicitMp4DirectUrl("https://cdn.example/video.mp4?sign=1"));
        assertFalse(OfflineVideoCache.isExplicitMp4DirectUrl("https://cdn.example/video.m3u8"));
        assertFalse(OfflineVideoCache.isExplicitMp4DirectUrl("https://jx.example/play?url=video.mp4"));
        assertFalse(OfflineVideoCache.isExplicitMp4DirectUrl("magnet:?xt=urn:btih:abc"));
    }

    @Test
    public void shutdownWaitsUntilCancelledChildWorkerExits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException expected) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                exited.countDown();
            }
        });

        assertTrue("worker must start before cancellation", started.await(2, TimeUnit.SECONDS));
        OfflineVideoCache.shutdownAndAwait(executor);
        assertEquals("parent may not resume while child still owns its output stream",
                0L, exited.getCount());
        assertTrue(executor.isTerminated());
    }

    @Test
    public void rangeResumeRequiresMatchingPartialResponse() {
        Request request = new Request.Builder().url("https://cdn.example/video.mp4").build();
        Response partial = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 1024-4095/4096")
                .build();
        assertTrue(OfflineVideoCache.acceptsRange(partial, 1024));

        Response ignoredRange = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build();
        assertFalse(OfflineVideoCache.acceptsRange(ignoredRange, 1024));

        Response wrongOffset = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 512-4095/4096")
                .build();
        assertFalse(OfflineVideoCache.acceptsRange(wrongOffset, 1024));

        Response completeRange = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes 1024-4095/4096")
                .build();
        assertEquals(4096L, OfflineVideoCache.contentRangeTotal(completeRange));

        Response unsatisfied = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(416)
                .message("Range Not Satisfiable")
                .header("Content-Range", "bytes */4096")
                .build();
        assertEquals(4096L, OfflineVideoCache.contentRangeTotal(unsatisfied));
    }

    @Test
    public void finiteHlsIsCacheableButParserPagesAndLiveCandidatesAreNotMp4Links() {
        assertTrue(OfflineVideoCache.isCacheableVodUrl("https://cdn.example/vod/index.m3u8?token=1"));
        assertTrue(OfflineVideoCache.isCacheableVodUrl("https://cdn.example/vod/episode.ts?token=1"));
        assertTrue(OfflineVideoCache.isCacheableVodUrl("https://cdn.example/vod/manifest.mpd?token=1"));
        assertTrue(OfflineVideoCache.isCacheableVodUrl("https://cdn.example/play?id=1&type=flv"));
        assertFalse(OfflineVideoCache.explicitMediaExtension("https://site.example/player?id=1").length() > 0);
        assertFalse(OfflineHlsTransformer.isHlsVodCandidate("https://site.example/player?id=1"));
        assertFalse(OfflineMediaTransformer.isConvertibleMediaUrl("https://site.example/player?id=1"));
        assertFalse(OfflineVideoCache.isCacheableVodUrl("https://site.example/player?id=1"));
        assertFalse(OfflineVideoCache.isExplicitMp4DirectUrl("https://cdn.example/vod/index.m3u8?token=1"));
    }

    @Test
    public void nonMp4MediaKeepsExplicitExtensionWhileTransformedMediaUsesMp4() {
        File keyed = OfflineVideoCache.cachedFileForDirectory(folder.getRoot(), "xgzy", "movie-1",
                "线路A", 2, "https://cdn.example/vod/episode.ts?token=1");
        assertNotNull(keyed);
        assertTrue(keyed.getName().endsWith(".ts"));
        File hls = OfflineVideoCache.cachedFileForDirectory(folder.getRoot(), "xgzy", "movie-1",
                "线路A", 2, "https://cdn.example/vod/index.m3u8?token=1");
        assertNotNull(hls);
        assertTrue(hls.getName().endsWith(".mp4"));
    }

    @Test
    public void successfulDownloadCommitsPartAndCacheCanBeHit() throws Exception {
        File part = folder.newFile("movie.mp4.part");
        assertTrue(OfflineVideoCache.copyStream(
                new ByteArrayInputStream("fake-mp4-payload".getBytes(StandardCharsets.UTF_8)),
                part, null));
        File target = new File(folder.getRoot(), "movie.mp4");
        assertTrue(OfflineVideoCache.commitPart(part, target));
        assertFalse(part.exists());
        assertTrue(OfflineVideoCache.isUsableCacheFile(target));

        File keyed = OfflineVideoCache.cachedFileForDirectory(folder.getRoot(), "xgzy", "movie-1",
                "线路A", 2, "https://cdn.example/movie.mp4?sign=1");
        assertNotNull(keyed);
        assertTrue(keyed.getName().endsWith(".mp4"));
        assertEquals(OfflineVideoCache.buildCacheKey("xgzy", "movie-1", "线路A", 2,
                "https://cdn.example/movie.mp4?sign=1"), OfflineVideoCache.buildCacheKey(
                "xgzy", "movie-1", "线路A", 2, "https://cdn.example/movie.mp4?sign=1"));
    }

    @Test
    public void logicalTaskKeyIgnoresRotatingPlaybackUrlAndLine() {
        String first = OfflineVideoCache.buildCacheKey("xgzy", "movie-stable", "线路A", 2,
                "https://cdn.example/a.mp4?sign=old");
        String second = OfflineVideoCache.buildCacheKey("xgzy", "movie-stable", "线路B", 2,
                "https://cdn.example/b.m3u8?sign=new");
        assertEquals("签名地址或线路变化不应创建第二条任务", first, second);
    }

    @Test
    public void cacheLookupReusesStableFileWhenPlaybackUrlChanges() throws Exception {
        final File filesDir = folder.newFolder("stable-cache-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String oldUrl = "https://cdn.example/episode.mp4?token=old";
        String newUrl = "https://cdn.example/episode.m3u8?token=new";
        String key = OfflineVideoCache.buildCacheKey("xgzy", "movie-stable", "线路A", 2, oldUrl);
        File generated = OfflineVideoCache.targetFileForExtension(context, key, ".mp4");
        assertTrue(generated.getParentFile().mkdirs() || generated.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), generated, null));
        File hit = OfflineVideoCache.getCachedFile(context, "xgzy", "movie-stable", "线路B", 2, newUrl);
        assertEquals(generated.getAbsolutePath(), hit.getAbsolutePath());
    }

    @Test
    public void cancelledDownloadIsNotCommittedAndPartIsRemoved() throws Exception {
        File part = folder.newFile("cancel.mp4.part");
        OfflineVideoCache.DownloadHandle handle = new OfflineVideoCache.DownloadHandle();
        handle.cancel();
        assertFalse(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), part, handle));
        assertFalse(OfflineVideoCache.commitPart(part, new File(folder.getRoot(), "cancel.mp4")));
        assertTrue(part.delete() || !part.exists());
        assertFalse(OfflineVideoCache.isUsableCacheFile(new File(folder.getRoot(), "cancel.mp4")));
    }

    @Test
    public void playlistPayloadIsRejectedEvenWhenUrlEndsWithMp4() throws Exception {
        File part = folder.newFile("playlist.mp4.part");
        assertFalse(OfflineVideoCache.copyStream(new ByteArrayInputStream(
                "#EXTM3U\n#EXT-X-TARGETDURATION:6".getBytes(StandardCharsets.UTF_8)), part, null));
        assertFalse(OfflineVideoCache.commitPart(part, new File(folder.getRoot(), "playlist.mp4")));
        assertTrue(part.delete() || !part.exists());
    }

    @Test
    public void cacheLookupHitsGeneratedTsFileForHls() throws Exception {
        final File filesDir = folder.newFolder("app-files");
        Context context = new ContextWrapper(null) {
            @Override
            public File getFilesDir() {
                return filesDir;
            }
        };
        String source = "xgzy";
        String movie = "65724";
        String flag = "线路A";
        int episode = 1;
        String hls = "https://cdn.example/vod/index.m3u8?token=1";
        String key = OfflineVideoCache.buildCacheKey(source, movie, flag, episode, hls);
        File generated = OfflineVideoCache.targetFileForExtension(context, key, ".ts");
        assertTrue(generated.getParentFile().mkdirs() || generated.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(
                new ByteArrayInputStream(new byte[]{0, 1, 2, 3}), generated, null));

        File hit = OfflineVideoCache.getCachedFile(context, source, movie, flag, episode, hls);
        assertEquals(generated.getAbsolutePath(), hit.getAbsolutePath());
        assertTrue(OfflineVideoCache.isUsableCacheFile(hit));
    }

    @Test
    public void progressPercentUsesCurrentReliableTotalWhenItChanges() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("progress-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/progress.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-progress", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-progress", "line", 1, url);

        OfflineVideoCache.markProgress(context, id, 500, 1000);
        assertEquals(50, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());
        OfflineVideoCache.markProgress(context, id, 500, 2000);
        assertEquals(25, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());
        OfflineVideoCache.markProgress(context, id, 1000, 2000);
        assertEquals(50, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());
        OfflineVideoCache.markProgress(context, id, 2000, 2000);
        // 下载中最多显示 99%，只有最终文件原子提交后才进入 100%。
        assertEquals(99, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());
        resetCacheState();
    }

    @Test
    public void hlsSegmentProgressUsesSegmentCountWithoutFakeByteTotal() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("segment-progress-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/vod/index.m3u8";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-segments", "line", 0,
                url, "影片", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-segments", "line", 0, url);
        OfflineVideoCache.markDownloading(context, id, -1);
        OfflineVideoCache.markSegmentProgress(context, id, 3, 10, 1024);
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(30, snapshot.getProgressPercent());
        assertEquals(-1L, snapshot.getTotalBytes());
        resetCacheState();
    }

    @Test
    public void unknownOrPartialTotalDoesNotExposeInflatedProgress() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("unknown-total-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/unknown-total.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-unknown-total", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-unknown-total", "line", 1, url);

        OfflineVideoCache.markDownloading(context, id, -1);
        OfflineVideoCache.markProgress(context, id, 99, -1);
        assertEquals(-1, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());

        // 多分片总量尚未探测完整时，即使当前已知部分接近完成，也不能显示 99%。
        OfflineVideoCache.markProgress(context, id, 990, 1001, false);
        OfflineVideoCache.TaskSnapshot partial = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(990, partial.getDownloadedBytes());
        assertEquals(-1, partial.getTotalBytes());
        assertEquals(-1, partial.getProgressPercent());

        OfflineVideoCache.markProgress(context, id, 990, 1000, true);
        assertEquals(99, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());
        resetCacheState();
    }

    @Test
    public void markDownloadingClearsPreviousProgress() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("reset-progress-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/reset-progress.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-reset-progress", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-reset-progress", "line", 1, url);
        OfflineVideoCache.markProgress(context, id, 990, 1000);
        OfflineVideoCache.markDownloading(context, id, -1);
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(0, snapshot.getDownloadedBytes());
        assertEquals(-1, snapshot.getTotalBytes());
        assertEquals(-1, snapshot.getProgressPercent());
        resetCacheState();
    }

    @Test
    public void completedTaskForcesEqualBytesAndHundredPercent() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("complete-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/complete.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-complete", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-complete", "line", 1, url);
        OfflineVideoCache.markProgress(context, id, 2, 100);
        File target = new File(filesDir, "complete.mp4");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}),
                target, null));
        OfflineVideoCache.markCompleted(context, id, target);
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(snapshot.getDownloadedBytes(), snapshot.getTotalBytes());
        assertEquals(100, snapshot.getProgressPercent());
        assertEquals(0, snapshot.getSpeedBytesPerSecond());
        resetCacheState();
    }

    @Test
    public void completedTaskUsesCommittedFileSizeOverStaleExpectedTotal() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("complete-size-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/complete-size.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-complete-size", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-complete-size", "line", 1, url);
        OfflineVideoCache.markProgress(context, id, 100, 1000);
        File target = new File(filesDir, "complete-size.mp4");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}),
                target, null));
        OfflineVideoCache.markCompleted(context, id, target);
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(4, snapshot.getDownloadedBytes());
        assertEquals(4, snapshot.getTotalBytes());
        assertEquals(100, snapshot.getProgressPercent());
        resetCacheState();
    }

    @Test
    public void queuedTaskCannotBePromotedToCompletedByLateCallback() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("queued-completion-guard");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/queued-guard.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-queued-guard", "line", 0,
                url, "测试", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-queued-guard", "line", 0, url);
        File target = OfflineVideoCache.targetFile(context, id, url);
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), target, null));

        // 任务仍是 QUEUED，模拟暂停后的旧线程迟到回调。
        OfflineVideoCache.markCompleted(context, id, target);
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(OfflineVideoCache.TaskStatus.QUEUED, snapshot.getStatus());
        assertFalse(target.exists());
        resetCacheState();
    }

    @Test
    public void retryClearsStaleProgressBeforeStartingAgain() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("retry-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "http://127.0.0.1:1/retry.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-retry", "line", 1,
                url, "测试", "1", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-retry", "line", 1, url);
        OfflineVideoCache.markProgress(context, id, 700, 1000);
        OfflineVideoCache.markFailed(context, id, "网络失败");

        assertTrue(OfflineVideoCache.retry(context, id));
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertFalse("重试不应把残留文件直接标为完成",
                snapshot.getStatus() == OfflineVideoCache.TaskStatus.COMPLETED);
        assertEquals(0, snapshot.getDownloadedBytes());
        assertEquals(-1, snapshot.getTotalBytes());
        assertEquals(-1, snapshot.getProgressPercent());
        assertEquals(0, snapshot.getSpeedBytesPerSecond());
        resetCacheState();
    }

    @Test
    public void retryDoesNotTreatNonCompletedTargetAsFinished() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("retry-residue-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "http://127.0.0.1:1/residue.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-residue", "line", 0,
                url, "残留测试", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-residue", "line", 0, url);
        OfflineVideoCache.markFailed(context, id, "上一轮失败");
        File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), target, null));

        assertTrue(OfflineVideoCache.retry(context, id));
        OfflineVideoCache.TaskSnapshot snapshot = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertFalse("重试不应把残留文件直接标为完成",
                snapshot.getStatus() == OfflineVideoCache.TaskStatus.COMPLETED);
        assertFalse("残留最终文件不能被重试直接视为完成", target.exists());
        resetCacheState();
    }

    @Test
    public void pauseThenRetryResumesExistingPartWithoutConcurrentWriter() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("resume-http-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        final byte[] payload = new byte[2 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);

        ServerSocket server = new ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.ExecutorService serverExecutor = Executors.newCachedThreadPool();
        AtomicBoolean serverRunning = new AtomicBoolean(true);
        Thread acceptThread = new Thread(() -> {
            while (serverRunning.get()) {
                try {
                    Socket socket = server.accept();
                    serverExecutor.execute(() -> {
                        try (Socket client = socket;
                             BufferedReader reader = new BufferedReader(
                                     new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                             OutputStream output = client.getOutputStream()) {
                            String range = null;
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                                    range = line.substring(6).trim();
                                }
                            }
                            long start = 0L;
                            if (range != null && range.startsWith("bytes=")) {
                                String value = range.substring("bytes=".length()).trim();
                                int dash = value.indexOf('-');
                                if (dash >= 0) start = Long.parseLong(value.substring(0, dash));
                            }
                            if (start < 0 || start >= payload.length) {
                                String response = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                                        + "Content-Range: bytes */" + payload.length + "\r\n"
                                        + "Connection: close\r\n\r\n";
                                output.write(response.getBytes(StandardCharsets.US_ASCII));
                                output.flush();
                                return;
                            }
                            long remaining = payload.length - start;
                            int code = start == 0 ? 200 : 206;
                            StringBuilder headers = new StringBuilder()
                                    .append("HTTP/1.1 ").append(code).append(code == 200 ? " OK\r\n" : " Partial Content\r\n")
                                    .append("Content-Type: video/mp4\r\n")
                                    .append("Content-Length: ").append(remaining).append("\r\n");
                            if (code == 206) {
                                headers.append("Content-Range: bytes ").append(start).append('-')
                                        .append(payload.length - 1L).append('/').append(payload.length).append("\r\n");
                            }
                            headers.append("Connection: close\r\n\r\n");
                            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
                            output.flush();
                            int offset = (int) start;
                            while (offset < payload.length) {
                                int count = Math.min(16 * 1024, payload.length - offset);
                                output.write(payload, offset, count);
                                output.flush();
                                offset += count;
                                Thread.sleep(5L);
                            }
                        } catch (Throwable ignored) {
                            // 暂停会主动关闭连接，这是预期路径。
                        }
                    });
                } catch (java.io.IOException ignored) {
                    if (serverRunning.get()) throw new RuntimeException(ignored);
                }
            }
        }, "offline-resume-http-accept");
        acceptThread.start();
        try {
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/resume.mp4";
            OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "resume-http", "line", 0,
                    url, "断点测试", "第1集", "西瓜");
            String id = OfflineVideoCache.buildCacheKey("xgzy", "resume-http", "line", 0, url);
            OfflineVideoCache.prefetch(context, "xgzy", "resume-http", "line", 0, url, null);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline
                    && OfflineVideoCache.getTaskSnapshots(context).get(0).getDownloadedBytes() < 64 * 1024L) {
                Thread.sleep(20L);
            }
            assertTrue("暂停前必须已经产生断点字节",
                    OfflineVideoCache.getTaskSnapshots(context).get(0).getDownloadedBytes() >= 64 * 1024L);
            assertTrue(OfflineVideoCache.pause(context, id));

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            OfflineVideoCache.TaskSnapshot paused;
            do {
                paused = OfflineVideoCache.getTaskSnapshots(context).get(0);
                if (paused.getStatus() == OfflineVideoCache.TaskStatus.PAUSED) break;
                Thread.sleep(20L);
            } while (System.nanoTime() < deadline);
            assertEquals(OfflineVideoCache.TaskStatus.PAUSED, paused.getStatus());
            File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
            File part = new File(target.getParentFile(), target.getName() + ".part");
            assertTrue("暂停后必须保留 .part", part.isFile() && part.length() > 0 && part.length() < payload.length);

            assertTrue(OfflineVideoCache.retry(context, id));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            OfflineVideoCache.TaskSnapshot completed;
            do {
                completed = OfflineVideoCache.getTaskSnapshots(context).get(0);
                if (completed.getStatus() == OfflineVideoCache.TaskStatus.COMPLETED) break;
                if (completed.getStatus() == OfflineVideoCache.TaskStatus.FAILED) break;
                Thread.sleep(30L);
            } while (System.nanoTime() < deadline);
            assertEquals("继续下载必须最终完成而不是重新创建竞态任务",
                    OfflineVideoCache.TaskStatus.COMPLETED, completed.getStatus());
            assertEquals(payload.length, target.length());
            byte[] actual = java.nio.file.Files.readAllBytes(target.toPath());
            assertEquals(payload.length, actual.length);
            for (int i = 0; i < payload.length; i += 8191) {
                assertEquals("断点续传不能拼接错位字节", payload[i], actual[i]);
            }
        } finally {
            serverRunning.set(false);
            server.close();
            acceptThread.join(1_000L);
            serverExecutor.shutdownNow();
            resetCacheState();
        }
    }

    @Test
    public void segmentResidueIsNeverACompletedCacheFile() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("segment-residue-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/segment-residue.m3u8";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "segment-residue", "line", 0,
                url, "分片残留", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "segment-residue", "line", 0, url);
        File base = OfflineVideoCache.targetFileForExtension(context, id, ".ts");
        File segment = new File(base.getParentFile(), base.getName() + ".seg-7");
        assertTrue(segment.getParentFile().mkdirs() || segment.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), segment, null));
        assertFalse(OfflineVideoCache.isFinalCacheFile(segment));
        assertFalse(OfflineVideoCache.isCompletedTask(context, id));
        assertTrue(OfflineVideoCache.getCachedFile(context, "xgzy", "segment-residue", "line", 0, url) == null);
        resetCacheState();
    }

    @Test
    public void deleteRemovesTaskAndAllLocalArtifacts() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("delete-artifacts-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/delete.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-delete", "line", 0,
                url, "删除测试", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-delete", "line", 0, url);
        File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), target, null));
        File part = new File(target.getParentFile(), target.getName() + ".part");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{4, 5}), part, null));
        File segment = new File(target.getParentFile(), target.getName() + ".seg-0");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{6}), segment, null));
        File segmentPart = new File(segment.getParentFile(), segment.getName() + ".part");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{7}), segmentPart, null));

        assertTrue(OfflineVideoCache.delete(context, id));
        assertFalse(target.exists());
        assertFalse(part.exists());
        assertFalse(segment.exists());
        assertFalse(segmentPart.exists());
        assertTrue(OfflineVideoCache.getTaskSnapshots(context).isEmpty());
        resetCacheState();
    }

    @Test
    public void deleteUnknownTaskIsNoOpAndActiveTaskRemovesItsHandleAndPart() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("delete-state-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        assertFalse(OfflineVideoCache.delete(context, "missing-task"));

        String url = "https://cdn.example/delete-active.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-delete-active", "line", 0,
                url, "删除活动任务", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-delete-active", "line", 0, url);
        File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        File part = new File(target.getParentFile(), target.getName() + ".part");
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2}), part, null));

        Field allActiveField = OfflineVideoCache.class.getDeclaredField("ALL_ACTIVE");
        allActiveField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, OfflineVideoCache.DownloadHandle> allActive =
                (java.util.Map<String, OfflineVideoCache.DownloadHandle>) allActiveField.get(null);
        OfflineVideoCache.DownloadHandle handle = new OfflineVideoCache.DownloadHandle();
        allActive.put(id, handle);

        assertTrue(OfflineVideoCache.delete(context, id));
        assertTrue("删除活动任务必须取消旧句柄", handle.isCancelled());
        assertFalse(part.exists());
        assertTrue(OfflineVideoCache.getTaskSnapshots(context).isEmpty());
        assertFalse("同一任务删除后再次删除应返回 false", OfflineVideoCache.delete(context, id));
        resetCacheState();
    }

    @Test
    public void speedUsesMonotonicOneSecondWindowAndIgnoresDuplicateCallbacks() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("speed-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/speed.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-speed", "line", 1,
                url, "测试", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-speed", "line", 1, url);
        OfflineVideoCache.markDownloading(context, id, -1);
        OfflineVideoCache.markProgressForTests(context, id, 0, -1, 0L, 0L);
        assertEquals(0L, OfflineVideoCache.getTaskSnapshots(context).get(0)
                .getSpeedBytesPerSecond());
        OfflineVideoCache.markProgressForTests(context, id, 250, -1, 250L, 250_000_000L);
        assertTrue("速度应在首个有效采样窗口后及时显示",
                OfflineVideoCache.getTaskSnapshots(context).get(0)
                        .getSpeedBytesPerSecond() > 0L);
        OfflineVideoCache.markProgressForTests(context, id, 1000, -1, 1000L, 1_000_000_000L);
        long speed = OfflineVideoCache.getTaskSnapshots(context).get(0).getSpeedBytesPerSecond();
        assertEquals(1000L, speed);
        OfflineVideoCache.markProgressForTests(context, id, 1000, -1, 1100L, 1_100_000_000L);
        long duplicateSpeed = OfflineVideoCache.getTaskSnapshots(context).get(0)
                .getSpeedBytesPerSecond();
        assertTrue("duplicate callback must not increase speed", duplicateSpeed <= speed);
        OfflineVideoCache.markProgressForTests(context, id, 1500, -1, 2500L, 2_500_000_000L);
        assertEquals("a post-stall sample must establish a fresh baseline", 0L,
                OfflineVideoCache.getTaskSnapshots(context).get(0).getSpeedBytesPerSecond());
        resetCacheState();
    }

    @Test
    public void normalizedRegistrationMergesMetadataIntoOneTask() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("registration-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String cleanUrl = "http://127.0.0.1:1/one.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-registration", "line", 2,
                "  " + cleanUrl + "  ", "影片", "第3集", "西瓜");
        OfflineVideoCache.prefetch(context, "xgzy", "movie-registration", "line", 2,
                cleanUrl, null);
        List<OfflineVideoCache.TaskSnapshot> tasks = OfflineVideoCache.getTaskSnapshots(context);
        assertEquals(1, tasks.size());
        assertEquals("影片", tasks.get(0).getMovieName());
        assertEquals("第3集", tasks.get(0).getEpisodeName());
        assertEquals(cleanUrl, tasks.get(0).getRemoteUrl());
        resetCacheState();
    }

    @Test
    public void taskOrderUsesInsertionOrderEvenWhenOlderTaskProgressChanges() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("stable-order-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String firstUrl = "https://cdn.example/order-first.mp4";
        String secondUrl = "https://cdn.example/order-second.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "order-first", "line", 0,
                firstUrl, "先加入", "第1集", "西瓜");
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "order-second", "line", 0,
                secondUrl, "后加入", "第1集", "西瓜");

        List<OfflineVideoCache.TaskSnapshot> initial = OfflineVideoCache.getTaskSnapshots(context);
        assertEquals("后加入", initial.get(0).getMovieName());
        assertEquals("先加入", initial.get(1).getMovieName());

        String firstId = OfflineVideoCache.buildCacheKey("xgzy", "order-first", "line", 0, firstUrl);
        OfflineVideoCache.markDownloading(context, firstId, 10_000L);
        OfflineVideoCache.markProgress(context, firstId, 9_000L, 10_000L);
        List<OfflineVideoCache.TaskSnapshot> afterProgress = OfflineVideoCache.getTaskSnapshots(context);
        assertEquals("进度变化不能把旧任务顶到最上面", "后加入", afterProgress.get(0).getMovieName());
        assertEquals("先加入", afterProgress.get(1).getMovieName());
        resetCacheState();
    }

    @Test
    public void downloadSlotsAllowFiveTasksAndQueueTheSixth() throws Exception {
        int heldSlots = 0;
        OfflineVideoCache.DownloadHandle sixth = new OfflineVideoCache.DownloadHandle();
        Thread waiter = null;
        AtomicBoolean sixthAcquired = new AtomicBoolean(false);
        try {
            for (int i = 0; i < OfflineVideoCache.MAX_ACTIVE_DOWNLOADS; i++) {
                assertTrue("the default download limit must allow five active tasks",
                        OfflineVideoCache.acquireDownloadSlot(new OfflineVideoCache.DownloadHandle()));
                heldSlots++;
            }

            CountDownLatch started = new CountDownLatch(1);
            waiter = new Thread(() -> {
                started.countDown();
                if (OfflineVideoCache.acquireDownloadSlot(sixth)) sixthAcquired.set(true);
            }, "offline-download-slot-test");
            waiter.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertFalse("the sixth task must wait while five tasks hold the slots",
                    sixthAcquired.get());

            OfflineVideoCache.releaseDownloadSlot();
            heldSlots--;
            waiter.join(1_000L);
            assertTrue("a queued task must start after a slot is released",
                    sixthAcquired.get());
            heldSlots++;
        } finally {
            sixth.cancel();
            if (waiter != null && waiter.isAlive()) {
                waiter.join(1_000L);
            }
            while (heldSlots-- > 0) OfflineVideoCache.releaseDownloadSlot();
        }
    }

    @Test
    public void directDownloadsActuallyRunFiveTasksInParallel() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("parallel-download-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ServerSocket server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.ExecutorService serverExecutor = Executors.newCachedThreadPool();
        AtomicBoolean serverRunning = new AtomicBoolean(true);
        Thread acceptThread = new Thread(() -> {
            while (serverRunning.get()) {
                try {
                    Socket socket = server.accept();
                    serverExecutor.execute(() -> {
                        int now = active.incrementAndGet();
                        maxActive.updateAndGet(previous -> Math.max(previous, now));
                        try (Socket client = socket;
                             BufferedReader reader = new BufferedReader(
                                     new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                             OutputStream output = client.getOutputStream()) {
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                // consume request headers
                            }
                            Thread.sleep(250L);
                            byte[] body = new byte[32 * 1024];
                            output.write(("HTTP/1.1 200 OK\\r\\nContent-Length: " + body.length
                                    + "\\r\\nConnection: close\\r\\n\\r\\n").getBytes(StandardCharsets.US_ASCII));
                            output.write(body);
                            output.flush();
                        } catch (Throwable ignored) {
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                } catch (java.io.IOException ignored) {
                    if (serverRunning.get()) throw new RuntimeException(ignored);
                }
            }
        }, "offline-test-http-accept");
        acceptThread.start();
        try {
            int port = server.getLocalPort();
            for (int i = 0; i < OfflineVideoCache.MAX_ACTIVE_DOWNLOADS; i++) {
                String url = "http://127.0.0.1:" + port + "/parallel-" + i + ".mp4";
                OfflineVideoCache.prepareTaskMetadata(context, "parallel", "movie-" + i,
                        "line", 0, url, "并发测试", "第1集", "测试源");
                OfflineVideoCache.prefetch(context, "parallel", "movie-" + i,
                        "line", 0, url, null);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                List<OfflineVideoCache.TaskSnapshot> tasks = OfflineVideoCache.getTaskSnapshots(context);
                if (tasks.size() == OfflineVideoCache.MAX_ACTIVE_DOWNLOADS
                        && tasks.stream().allMatch(task ->
                        task.getStatus() == OfflineVideoCache.TaskStatus.COMPLETED)) break;
                Thread.sleep(25L);
            }
            assertEquals("five independent tasks should be active at the same time",
                    OfflineVideoCache.MAX_ACTIVE_DOWNLOADS, maxActive.get());
        } finally {
            serverRunning.set(false);
            server.close();
            acceptThread.join(1_000L);
            serverExecutor.shutdownNow();
            resetCacheState();
        }
    }

    @Test
    public void lateCompletionCannotOverridePausedTask() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("paused-completion-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/paused-race.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "paused-race", "line", 0,
                url, "暂停竞态", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "paused-race", "line", 0, url);
        java.lang.reflect.Field tasksField = OfflineVideoCache.class.getDeclaredField("TASKS");
        tasksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> tasks = (java.util.Map<String, ?>) tasksField.get(null);
        Object record = tasks.get(id);
        java.lang.reflect.Field statusField = record.getClass().getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(record, OfflineVideoCache.TaskStatus.PAUSED);
        File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                target, null));
        OfflineVideoCache.markCompleted(context, id, target);
        assertEquals(OfflineVideoCache.TaskStatus.PAUSED,
                OfflineVideoCache.getTaskSnapshots(context).get(0).getStatus());
        assertFalse("late completion must not leave a playable final file", target.exists());
        resetCacheState();
    }

    @Test
    public void staleHandleCallbacksAfterResumeCannotCompleteOrDistortNewTask() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("resume-race-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/resume-race.mp4";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-resume-race", "line", 0,
                url, "继续竞态", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-resume-race", "line", 0, url);

        Field allActiveField = OfflineVideoCache.class.getDeclaredField("ALL_ACTIVE");
        allActiveField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, OfflineVideoCache.DownloadHandle> allActive =
                (java.util.Map<String, OfflineVideoCache.DownloadHandle>) allActiveField.get(null);
        OfflineVideoCache.DownloadHandle oldHandle = new OfflineVideoCache.DownloadHandle();
        allActive.put(id, oldHandle);
        OfflineVideoCache.markDownloading(context, id, 1000, oldHandle);
        OfflineVideoCache.markProgress(context, id, 400, 1000, true, oldHandle);
        assertEquals(40, OfflineVideoCache.getTaskSnapshots(context).get(0).getProgressPercent());

        assertTrue(OfflineVideoCache.pause(context, id));
        OfflineVideoCache.DownloadHandle newHandle = new OfflineVideoCache.DownloadHandle();
        allActive.put(id, newHandle);
        OfflineVideoCache.markDownloading(context, id, -1, newHandle);

        // 旧请求在新一轮已经开始后才回调，进度和完成状态都必须被丢弃。
        OfflineVideoCache.markProgress(context, id, 1000, 1000, true, oldHandle);
        File target = OfflineVideoCache.targetFileForExtension(context, id, ".mp4");
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        assertTrue(OfflineVideoCache.copyStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), target, null));
        OfflineVideoCache.markCompleted(context, id, target, oldHandle);
        OfflineVideoCache.TaskSnapshot afterStaleCallbacks = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(OfflineVideoCache.TaskStatus.DOWNLOADING, afterStaleCallbacks.getStatus());
        assertEquals(0, afterStaleCallbacks.getDownloadedBytes());
        assertEquals(-1, afterStaleCallbacks.getTotalBytes());
        assertEquals(-1, afterStaleCallbacks.getProgressPercent());
        assertEquals(0L, afterStaleCallbacks.getSpeedBytesPerSecond());

        OfflineVideoCache.markCompleted(context, id, target, newHandle);
        assertEquals(OfflineVideoCache.TaskStatus.COMPLETED,
                OfflineVideoCache.getTaskSnapshots(context).get(0).getStatus());
        resetCacheState();
    }

    @Test
    public void pauseThenResumeRehydratesExistingHlsSegmentProgress() throws Exception {
        resetCacheState();
        final File filesDir = folder.newFolder("hls-resume-progress-files");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
        };
        String url = "https://cdn.example/hls-resume-progress.m3u8";
        OfflineVideoCache.prepareTaskMetadata(context, "xgzy", "movie-hls-resume", "line", 0,
                url, "HLS断点", "第1集", "西瓜");
        String id = OfflineVideoCache.buildCacheKey("xgzy", "movie-hls-resume", "line", 0, url);

        Field allActiveField = OfflineVideoCache.class.getDeclaredField("ALL_ACTIVE");
        allActiveField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, OfflineVideoCache.DownloadHandle> allActive =
                (java.util.Map<String, OfflineVideoCache.DownloadHandle>) allActiveField.get(null);

        // 模拟第一轮已经落盘了 4/10 个 HLS 分片；这里只调用状态接口，避免网络和时间等待。
        OfflineVideoCache.DownloadHandle firstHandle = new OfflineVideoCache.DownloadHandle();
        allActive.put(id, firstHandle);
        OfflineVideoCache.markDownloading(context, id, -1, firstHandle);
        OfflineVideoCache.markSegmentProgress(context, id, 4, 10, 4 * 1024L, firstHandle);
        OfflineVideoCache.TaskSnapshot beforePause = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(OfflineVideoCache.TaskStatus.DOWNLOADING, beforePause.getStatus());
        assertEquals(4 * 1024L, beforePause.getDownloadedBytes());
        assertEquals(40, beforePause.getProgressPercent());

        assertTrue(OfflineVideoCache.pause(context, id));
        OfflineVideoCache.TaskSnapshot paused = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(OfflineVideoCache.TaskStatus.PAUSED, paused.getStatus());
        assertEquals("暂停不能清空已完成分片的字节数", 4 * 1024L, paused.getDownloadedBytes());
        assertEquals("暂停不能清空已完成分片的进度", 40, paused.getProgressPercent());

        // 继续下载会创建新句柄；新一轮收到复用分片回调后，进度必须恢复，不能停在 0/-1。
        OfflineVideoCache.DownloadHandle resumedHandle = new OfflineVideoCache.DownloadHandle();
        allActive.put(id, resumedHandle);
        OfflineVideoCache.markDownloading(context, id, -1, resumedHandle);
        OfflineVideoCache.markSegmentProgress(context, id, 4, 10, 4 * 1024L, resumedHandle);
        OfflineVideoCache.TaskSnapshot afterResume = OfflineVideoCache.getTaskSnapshots(context).get(0);
        assertEquals(OfflineVideoCache.TaskStatus.DOWNLOADING, afterResume.getStatus());
        assertEquals("继续后复用分片不能把字节数归零", 4 * 1024L, afterResume.getDownloadedBytes());
        assertEquals("继续后复用分片不能把进度归零", 40, afterResume.getProgressPercent());
        assertTrue("继续后的分片快照必须比暂停快照新", afterResume.getUpdatedAt() > paused.getUpdatedAt());
        assertFalse("仅复用部分分片不能误报完成", afterResume.getStatus()
                == OfflineVideoCache.TaskStatus.COMPLETED);
        resetCacheState();
    }

    private static void resetCacheState() throws Exception {
        Field loaded = OfflineVideoCache.class.getDeclaredField("tasksLoaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        Field index = OfflineVideoCache.class.getDeclaredField("taskIndexFile");
        index.setAccessible(true);
        index.set(null, null);
        Field loadedDirectory = OfflineVideoCache.class.getDeclaredField("loadedFilesDirectory");
        loadedDirectory.setAccessible(true);
        loadedDirectory.set(null, null);
        Field tasks = OfflineVideoCache.class.getDeclaredField("TASKS");
        tasks.setAccessible(true);
        ((java.util.Map<?, ?>) tasks.get(null)).clear();
        Field states = OfflineVideoCache.class.getDeclaredField("PROGRESS_STATES");
        states.setAccessible(true);
        ((java.util.Map<?, ?>) states.get(null)).clear();
        Field active = OfflineVideoCache.class.getDeclaredField("ACTIVE");
        active.setAccessible(true);
        Field allActive = OfflineVideoCache.class.getDeclaredField("ALL_ACTIVE");
        allActive.setAccessible(true);
        // 先取消后台请求，再清空映射；否则旧任务可能在下一个测试切换目录后回调并重建状态。
        cancelHandles((java.util.Map<?, ?>) active.get(null));
        cancelHandles((java.util.Map<?, ?>) allActive.get(null));
        ((java.util.Map<?, ?>) active.get(null)).clear();
        ((java.util.Map<?, ?>) allActive.get(null)).clear();
    }

    private static void cancelHandles(java.util.Map<?, ?> handles) {
        for (Object value : handles.values()) {
            if (value instanceof OfflineVideoCache.DownloadHandle) {
                ((OfflineVideoCache.DownloadHandle) value).cancel();
            }
        }
    }
}
