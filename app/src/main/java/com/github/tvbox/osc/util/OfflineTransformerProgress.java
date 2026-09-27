package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 为 Media3 Transformer 补充真实网络字节和导出百分比反馈。 */
final class OfflineTransformerProgress {
    private OfflineTransformerProgress() {
    }

    static final class Counter {
        final AtomicLong bytes = new AtomicLong();
    }

    static DataSource.Factory countingFactory(DataSource.Factory delegate, Context context,
                                              String taskId, Counter counter,
                                              OfflineVideoCache.DownloadHandle handle) {
        return () -> new CountingDataSource(delegate.createDataSource(), context, taskId,
                counter, handle);
    }

    static final class Poller implements Runnable {
        private static final long INTERVAL_MS = 500L;

        private final Handler handler;
        private final Transformer transformer;
        private final Context context;
        private final String taskId;
        private final OfflineVideoCache.DownloadHandle handle;
        private final ProgressHolder holder = new ProgressHolder();
        private final AtomicBoolean stopped = new AtomicBoolean();

        Poller(Handler handler, Transformer transformer, Context context, String taskId,
               OfflineVideoCache.DownloadHandle handle) {
            this.handler = handler;
            this.transformer = transformer;
            this.context = context;
            this.taskId = taskId;
            this.handle = handle;
        }

        void start() {
            handler.post(this);
        }

        void stop() {
            if (stopped.compareAndSet(false, true)) handler.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (stopped.get()) return;
            try {
                int state = transformer.getProgress(holder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    OfflineVideoCache.markTransformerProgress(context, taskId, holder.progress, handle);
                }
            } catch (Throwable ignored) {
                // Transformer may be torn down while a final poll is queued.
            }
            if (!stopped.get()) handler.postDelayed(this, INTERVAL_MS);
        }
    }

    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        private final Context context;
        private final String taskId;
        private final Counter counter;
        private final OfflineVideoCache.DownloadHandle handle;

        CountingDataSource(DataSource delegate, Context context, String taskId, Counter counter,
                           OfflineVideoCache.DownloadHandle handle) {
            this.delegate = delegate;
            this.context = context;
            this.taskId = taskId;
            this.counter = counter;
            this.handle = handle;
        }

        @Override
        public void addTransferListener(androidx.media3.datasource.TransferListener listener) {
            delegate.addTransferListener(listener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            return delegate.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                long downloaded = counter.bytes.addAndGet(read);
                OfflineVideoCache.markProgress(context, taskId, downloaded, -1, false, handle);
            }
            return read;
        }

        @Override
        @Nullable
        public android.net.Uri getUri() {
            return delegate.getUri();
        }

        @Override
        public Map<String, java.util.List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
