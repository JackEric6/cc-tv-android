package com.github.tvbox.osc.player.danmu;

import android.graphics.Color;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Danmu;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import master.flame.danmaku.danmaku.model.AlphaValue;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.Duration;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.SpecialDanmaku;
import master.flame.danmaku.danmaku.model.android.DanmakuFactory;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.util.DanmakuUtils;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Parser extends BaseDanmakuParser {
    public interface ParseResultListener {
        void onParsed(int renderedCount);
    }

    public interface CancelChecker {
        boolean isCancelled();

        default void onCall(Call call) {
        }
    }

    private static final long HTTP_TIMEOUT_MS = 12 * 1000L;
    private static final X509TrustManager TRUST_ALL_CERT = new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[]{};
        }
    };
    private static final HostnameVerifier TRUST_ALL_HOSTNAME = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };
    private static volatile OkHttpClient HTTP_CLIENT = buildHttpClient(false);
    private static volatile OkHttpClient UNSAFE_HTTP_CLIENT = buildHttpClient(true);
    private final Danmu danmu;
    private final CancelChecker cancelChecker;
    private volatile ParseResultListener parseResultListener;
    private float scaleX;
    private float scaleY;
    private int index;

    public Parser(String input) {
        this(input, null);
    }

    public Parser(String input, CancelChecker cancelChecker) {
        this.cancelChecker = cancelChecker;
        this.danmu = Danmu.fromXml(resolveContent(input));
    }

    public int getDanmuCount() {
        return danmu.getData().size();
    }

    public void setParseResultListener(ParseResultListener listener) {
        parseResultListener = listener;
    }

    private String resolveContent(String input) {
        if (isCancelled()) return "";
        if (TextUtils.isEmpty(input)) return "";
        String source = input.trim();
        if (source.startsWith("file")) return FileUtils.read(source);
        if (source.startsWith("http")) {
            try {
                return loadHttp(source, false);
            } catch (Throwable th) {
                if (source.startsWith("https") && isSslError(th)) {
                    try {
                        LOG.i("echo-danmu ssl verify failed, retry unsafe ssl");
                        return loadHttp(source, true);
                    } catch (Throwable retryError) {
                        LOG.e("echo-danmu unsafe ssl retry error: " + retryError.getMessage());
                    }
                }
                LOG.e("echo-danmu load error: " + th.getMessage());
                return "";
            }
        }
        return source;
    }

    private String loadHttp(String source, boolean unsafeSsl) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (isCancelled()) return "";
            try (Response response = unsafeSsl ? executeUnsafeHttp(source) : executeHttp(source)) {
                if (!response.isSuccessful()) {
                    HttpStatusException error = new HttpStatusException(response.code());
                    if (!error.isRetryable()) throw error;
                    lastError = error;
                } else {
                    String content = readBody(response);
                    if (!TextUtils.isEmpty(content.trim())) {
                        LOG.i("echo-danmu http code: " + response.code()
                                + ", encoding: " + response.header("Content-Encoding", "")
                                + ", length: " + content.length()
                                + (attempt == 0 ? "" : ", retry=" + attempt));
                        return content;
                    }
                    lastError = new IOException("empty danmaku response");
                }
            } catch (IOException e) {
                if (isSslError(e) || attempt >= 1 || !isRetryable(e)) throw e;
                lastError = e;
            }
            if (attempt < 1) waitBeforeRetry(attempt);
        }
        throw lastError == null ? new IOException("danmaku request failed") : lastError;
    }

    private boolean isRetryable(IOException error) {
        if (isSslError(error)) return false;
        return !(error instanceof HttpStatusException) || ((HttpStatusException) error).isRetryable();
    }

    private void waitBeforeRetry(int attempt) throws IOException {
        if (isCancelled()) throw new IOException("danmaku request cancelled");
        try {
            Thread.sleep(300L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("danmaku retry interrupted", e);
        }
    }

    private static final class HttpStatusException extends IOException {
        private final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }

        boolean isRetryable() {
            return code == 408 || code == 429 || code >= 500;
        }
    }

    private boolean isCancelled() {
        return cancelChecker != null && cancelChecker.isCancelled();
    }

    private static OkHttpClient buildHttpClient(boolean unsafeSsl) {
        OkHttpClient base = OkGoHelper.getDefaultClient();
        OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder().proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator());
        builder.readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.retryOnConnectionFailure(true);
        if (unsafeSsl) {
            SSLSocketFactory sslSocketFactory = new SSLSocketFactoryCompat(TRUST_ALL_CERT);
            builder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERT);
            builder.hostnameVerifier(TRUST_ALL_HOSTNAME);
        }
        return builder.build();
    }

    public static synchronized void resetHttpClient() {
        HTTP_CLIENT = buildHttpClient(false);
        UNSAFE_HTTP_CLIENT = buildHttpClient(true);
    }

    private okhttp3.Response executeHttp(String source) throws IOException {
        Request request = new Request.Builder().url(source).get().build();
        Call call = HTTP_CLIENT.newCall(request);
        if (cancelChecker != null) cancelChecker.onCall(call);
        if (isCancelled()) call.cancel();
        return call.execute();
    }

    private okhttp3.Response executeUnsafeHttp(String source) throws IOException {
        Request request = new Request.Builder().url(source).get().build();
        Call call = UNSAFE_HTTP_CLIENT.newCall(request);
        if (cancelChecker != null) cancelChecker.onCall(call);
        if (isCancelled()) call.cancel();
        return call.execute();
    }

    private boolean isSslError(Throwable th) {
        while (th != null) {
            if (th instanceof SSLException) return true;
            th = th.getCause();
        }
        return false;
    }

    private String readBody(okhttp3.Response response) throws IOException {
        if (response.body() == null) return "";
        byte[] bytes = response.body().bytes();
        if (looksXml(bytes)) return new String(bytes, "UTF-8");
        String encoding = response.header("Content-Encoding", "");
        if ("gzip".equalsIgnoreCase(encoding)) {
            bytes = readAll(new GZIPInputStream(new ByteArrayInputStream(bytes)));
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            bytes = inflate(bytes);
        }
        return new String(bytes, "UTF-8");
    }

    private boolean looksXml(byte[] bytes) {
        if (bytes == null) return false;
        for (byte value : bytes) {
            char ch = (char) (value & 0xff);
            if (Character.isWhitespace(ch)) continue;
            return ch == '<';
        }
        return false;
    }

    private byte[] inflate(byte[] bytes) throws IOException {
        try {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            Inflater inflater = new Inflater(true);
            try {
                return readAll(new InflaterInputStream(new ByteArrayInputStream(bytes), inflater));
            } finally {
                inflater.end();
            }
        }
    }

    private byte[] readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    @Override
    protected Danmakus parse() {
        Danmakus result = new Danmakus(IDanmakus.ST_BY_TIME);
        int renderedCount = 0;
        for (Danmu.Data data : danmu.getData()) {
            BaseDanmaku danmaku = createDanmaku(data);
            if (danmaku == null) continue;
            synchronized (result.obtainSynchronizer()) {
                result.addItem(danmaku);
            }
            renderedCount++;
        }
        LOG.i("echo-danmu rendered count: " + renderedCount);
        ParseResultListener listener = parseResultListener;
        if (listener != null) {
            try {
                listener.onParsed(renderedCount);
            } catch (Throwable th) {
                LOG.e("echo-danmu parse result callback error: " + th.getMessage());
            }
        }
        return result;
    }

    @Override
    public BaseDanmakuParser setDisplayer(IDisplayer display) {
        super.setDisplayer(display);
        scaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH;
        scaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT;
        return this;
    }

    private BaseDanmaku createDanmaku(Danmu.Data data) {
        try {
            String[] values = data.getParam().split(",");
            if (values.length < 4) return null;
            int type = Integer.parseInt(values[1]);
            BaseDanmaku item = mContext.mDanmakuFactory.createDanmaku(type, mContext);
            if (item == null) return null;
            long time = (long) (Float.parseFloat(values[0]) * 1000);
            float size = Float.parseFloat(values[2]) * Math.max(1.0f, mDispDensity - 0.6f);
            int color = DanmuHelper.useRandomColor()
                    ? DanmuHelper.randomColor()
                    : (int) ((0x00000000ff000000L | Long.parseLong(values[3])) & 0x00000000ffffffffL);
            item.setTime(time);
            item.setTimer(mTimer);
            item.textSize = size;
            item.textColor = color;
            item.textShadowColor = color <= Color.BLACK ? Color.WHITE : Color.BLACK;
            item.flags = mContext.mGlobalFlagValues;
            item.index = index++;
            DanmakuUtils.fillText(item, decodeXmlString(data.getText()));
            if (item.getType() == BaseDanmaku.TYPE_SPECIAL
                    && data.getText().startsWith("[")
                    && data.getText().endsWith("]")
                    && !setSpecial(item)) {
                return null;
            }
            return item;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean setSpecial(BaseDanmaku item) {
        String[] textArr;
        try {
            JSONArray jsonArray = new JSONArray(item.text);
            textArr = new String[jsonArray.length()];
            for (int i = 0; i < textArr.length; i++) {
                textArr[i] = jsonArray.getString(i);
            }
        } catch (JSONException e) {
            return false;
        }
        if (textArr.length < 5 || TextUtils.isEmpty(textArr[4])) return false;
        try {
            DanmakuUtils.fillText(item, textArr[4]);
            float beginX = Float.parseFloat(textArr[0]);
            float beginY = Float.parseFloat(textArr[1]);
            float endX = beginX;
            float endY = beginY;
            String[] alphaArr = textArr[2].split("-");
            int beginAlpha = (int) (AlphaValue.MAX * Float.parseFloat(alphaArr[0]));
            int endAlpha = alphaArr.length > 1
                    ? (int) (AlphaValue.MAX * Float.parseFloat(alphaArr[1]))
                    : beginAlpha;
            long alphaDuration = (long) (Float.parseFloat(textArr[3]) * 1000);
            long translationDuration = alphaDuration;
            long translationStartDelay = 0;
            float rotateY = 0;
            float rotateZ = 0;
            if (textArr.length >= 7) {
                rotateZ = Float.parseFloat(textArr[5]);
                rotateY = Float.parseFloat(textArr[6]);
            }
            if (textArr.length >= 11) {
                endX = Float.parseFloat(textArr[7]);
                endY = Float.parseFloat(textArr[8]);
                if (!TextUtils.isEmpty(textArr[9])) translationDuration = Long.parseLong(textArr[9]);
                if (!TextUtils.isEmpty(textArr[10])) translationStartDelay = (long) Float.parseFloat(textArr[10]);
            }
            if (isPercentageNumber(textArr[0])) beginX *= DanmakuFactory.BILI_PLAYER_WIDTH;
            if (isPercentageNumber(textArr[1])) beginY *= DanmakuFactory.BILI_PLAYER_HEIGHT;
            if (textArr.length >= 8 && isPercentageNumber(textArr[7])) endX *= DanmakuFactory.BILI_PLAYER_WIDTH;
            if (textArr.length >= 9 && isPercentageNumber(textArr[8])) endY *= DanmakuFactory.BILI_PLAYER_HEIGHT;
            item.duration = new Duration(alphaDuration);
            item.rotationZ = rotateZ;
            item.rotationY = rotateY;
            mContext.mDanmakuFactory.fillTranslationData(item, beginX, beginY, endX, endY,
                    translationDuration, translationStartDelay, scaleX, scaleY);
            mContext.mDanmakuFactory.fillAlphaData(item, beginAlpha, endAlpha, alphaDuration);
            if (textArr.length >= 12 && "true".equalsIgnoreCase(textArr[11])) {
                item.textShadowColor = Color.TRANSPARENT;
            }
            if (textArr.length >= 14) {
                ((SpecialDanmaku) item).isQuadraticEaseOut = "0".equals(textArr[13]);
            }
            if (textArr.length >= 15 && !TextUtils.isEmpty(textArr[14])) {
                fillLinePath(item, textArr[14]);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void fillLinePath(BaseDanmaku item, String motionPathString) {
        String motionPath = motionPathString.substring(1);
        if (TextUtils.isEmpty(motionPath)) return;
        String[] pointStrArray = motionPath.split("L");
        float[][] points = new float[pointStrArray.length][2];
        for (int i = 0; i < pointStrArray.length; i++) {
            String[] pointArray = pointStrArray[i].split(",");
            if (pointArray.length < 2) return;
            points[i][0] = Float.parseFloat(pointArray[0]);
            points[i][1] = Float.parseFloat(pointArray[1]);
        }
        DanmakuFactory.fillLinePathData(item, points, scaleX, scaleY);
    }

    private boolean isPercentageNumber(String number) {
        return number != null && number.contains(".");
    }

    private String decodeXmlString(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
