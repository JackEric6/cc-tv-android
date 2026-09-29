package com.github.tvbox.osc.server;

import android.util.Base64;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 荐片公开客户端使用的运行时安全通道。
 *
 * <p>这里仅实现前端已公开使用的请求封装：请求认证采用 HMAC-SHA256，POST
 * body 和响应 envelope 使用 AES-GCM。播放地址仍由服务端按集、按线路动态返回。</p>
 */
final class JianpianCrypto {
    static final String BASE_URL = "https://jianpian.com";
    static final String API_VERSION = "5.0.60";
    static final String CLIENT_APP = "jp";
    static final String CLIENT_PLATFORM = "android";
    static final String CRYPTO_VERSION = "1";
    static final String ENCRYPTED_CONTENT_TYPE = "application/vnd.jp.encrypted+json";

    private static final SecureRandom RANDOM = new SecureRandom();

    private JianpianCrypto() {
    }

    static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    static String base64Url(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    static byte[] decodeBase64Url(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    static String requestSignature(String method,
                                   String requestUri,
                                   long timestamp,
                                   String requestNonce,
                                   byte[] body,
                                   String credential,
                                   String installationId) throws Exception {
        byte[] bodyDigest = sha256(body == null ? new byte[0] : body);
        String message = method + "\n"
                + requestUri + "\n"
                + timestamp + "\n"
                + requestNonce + "\n"
                + hex(bodyDigest);
        return base64Url(hmac(deriveKey(credential, installationId, "jp-api-request-auth-v1"),
                message.getBytes(StandardCharsets.UTF_8)));
    }

    static JsonObject encryptRequest(String method,
                                     String requestUri,
                                     long timestamp,
                                     String requestNonce,
                                     byte[] body,
                                     String credential,
                                     String installationId) throws Exception {
        byte[] nonce = decodeBase64Url(requestNonce);
        String aad = "jp-api-request-v1\n"
                + method + "\n"
                + requestUri + "\n"
                + timestamp + "\n"
                + requestNonce + "\n"
                + installationId;
        byte[] encrypted = aesGcm(true,
                deriveKey(credential, installationId, "jp-api-request-body-v1"),
                nonce,
                body,
                aad.getBytes(StandardCharsets.UTF_8));
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 1);
        envelope.addProperty("alg", "A256GCM");
        envelope.addProperty("ts", timestamp);
        envelope.addProperty("data", base64Url(encrypted));
        return envelope;
    }

    static byte[] decryptResponse(JsonObject envelope,
                                  int status,
                                  String method,
                                  String requestUri,
                                  long timestamp,
                                  String requestNonce,
                                  String credential,
                                  String installationId) throws Exception {
        if (envelope == null || envelope.get("nonce") == null || envelope.get("data") == null) {
            throw new IllegalStateException("荐片响应缺少加密 envelope");
        }
        byte[] nonce = decodeBase64Url(envelope.get("nonce").getAsString());
        byte[] encrypted = decodeBase64Url(envelope.get("data").getAsString());
        String aad = "jp-api-response-v1\n"
                + method + "\n"
                + requestUri + "\n"
                + status + "\n"
                + timestamp + "\n"
                + requestNonce + "\n"
                + installationId;
        return aesGcm(false,
                deriveKey(credential, installationId, "jp-api-response-body-v1"),
                nonce,
                encrypted,
                aad.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] deriveKey(String credential, String installationId, String purpose) throws Exception {
        byte[] seed = sha256(concat("jp-api-v1".getBytes(StandardCharsets.UTF_8),
                new byte[]{0}, installationId.getBytes(StandardCharsets.UTF_8)));
        byte[] root = hmac(seed, credential.getBytes(StandardCharsets.UTF_8));
        return hmac(root, concat(purpose.getBytes(StandardCharsets.UTF_8), new byte[]{1}));
    }

    private static byte[] aesGcm(boolean encrypt,
                                 byte[] key,
                                 byte[] nonce,
                                 byte[] input,
                                 byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length += value.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }
}
