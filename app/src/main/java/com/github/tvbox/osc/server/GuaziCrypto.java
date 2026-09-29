package com.github.tvbox.osc.server;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 瓜子影视 App 接口的 AES + RSA 请求封装。 */
final class GuaziCrypto {
    static final String BASE_URL = "https://api.anctjd.com";
    static final String API_VERSION = "3.0.5.2";
    static final String PACKAGE_NAME = "com.xcf8fa289d.s53f6f7725.hc2d9bb51620260914";
    static final String VERSION_CODE = "2608011";

    private static final String SIGN_SALT = "*&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br";
    private static final String RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDUM5+/y8sPsWkd1/RQS64X259EUwxFXFE5HlA65MqrxnPs0JqoSRojSDy5QhwvROlaD6TwRQHKMY2OAZ6SnQeUJsChTEFIR9qUkwrs3/MVUMxjsv6JS6Oe/juclyJGTgVmDhB55EafXsD0SQYVj/QXXsxR6ewR5E2kL52yAAD4yQIDAQAB";
    /* 这是瓜子客户端内置的 PKCS#8 私钥，用于解包 response_key 的会话密钥。 */
    private static final String RSA_PRIVATE_KEY =
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGAe6hKrWLi1zQmjTT1ozbE4QdFeJGNxubxld6GrFGximxfMsMB6BpJhpcTouAqywAFppiKetUBBbXwYsYU1wNr648XVmPmCMCy4rY8vdliFnbMUj086DU6Z+/oXBdWU3/b1G0DN3E9wULRSwcKZT3wj/cCI1vsCm3gj2R5SqkA9Y0CAwEAAQKBgAJH+4CxV0/zBVcLiBCHvSANm0l7HetybTh/j2p0Y1sTXro4ALwAaCTUeqdBjWiLSo9lNwDHFyq8zX90+gNxa7c5EqcWV9FmlVXr8VhfBzcZo1nXeNdXFT7tQ2yah/odtdcx+vRMSGJd1t/5k5bDd9wAvYdIDblMAg+wiKKZ5KcdAkEA1cCakEN4NexkF5tHPRrR6XOY/XHfkqXxEhMqmNbB9U34saTJnLWIHC8IXys6Qmzz30TtzCjuOqKRRy+FMM4TdwJBAJQZFPjsGC+RqcG5UvVMiMPhnwe/bXEehShK86yJK/g/UiKrO87h3aEu5gcJqBygTq3BBBoH2md3pr/W+hUMWBsCQQChfhTIrdDinKi6lRxrdBnn0Ohjg2cwuqK5zzU9p/N+S9x7Ck8wUI53DKm8jUJE8WAG7WLj/oCOWEh+ic6NIwTdAkEAj0X8nhx6AXsgCYRql1klbqtVmL8+95KZK7PnLWG/IfjQUy3pPGoSaZ7fdquG8bq8oyf5+dzjE/oTXcByS+6XRQJAP/5ciy1bL3NhUhsaOVy55MHXnPjdcTX0FaLi+ybXZIfIQ2P4rb19mVq1feMbCXhz+L1rG8oat5lYKfpe8k83ZA==";

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private GuaziCrypto() {
    }

    static Map<String, String> createForm(JsonObject params, String token, long time) throws Exception {
        String key = randomText(16);
        String iv = randomText(16);
        String requestKey = aesEncrypt(GSON.toJson(params), key, iv);
        JsonObject keyObject = new JsonObject();
        keyObject.addProperty("key", key);
        keyObject.addProperty("iv", iv);
        String keys = rsaEncrypt(GSON.toJson(keyObject));
        String signatureText = "token_id=,token=" + value(token)
                + ",phone_type=1,request_key=" + requestKey
                + ",app_id=1,time=" + time + ",keys=" + keys;
        String signature = md5Hex(signatureText + SIGN_SALT);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", value(token));
        form.put("token_id", "");
        form.put("phone_type", "1");
        form.put("time", String.valueOf(time));
        form.put("phone_model", "Android-TVBox");
        form.put("keys", keys);
        form.put("request_key", requestKey);
        form.put("signature", signature);
        form.put("app_id", "1");
        form.put("ad_version", "1");
        return form;
    }

    static JsonObject decodeResponse(String raw) throws Exception {
        JsonObject outer = JsonParser.parseString(raw).getAsJsonObject();
        if (!outer.has("data") || !outer.get("data").isJsonObject()) return new JsonObject();
        JsonObject data = outer.getAsJsonObject("data");
        String sessionJson = rsaDecrypt(data.get("keys").getAsString());
        JsonObject session = JsonParser.parseString(sessionJson).getAsJsonObject();
        String key = session.get("key").getAsString();
        String iv = session.get("iv").getAsString();
        String responseKey = data.get("response_key").getAsString();
        return JsonParser.parseString(aesDecrypt(responseKey, key, iv)).getAsJsonObject();
    }

    static int responseCode(String raw) {
        try {
            return JsonParser.parseString(raw).getAsJsonObject().get("code").getAsInt();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static String responseMessage(String raw) {
        try {
            return JsonParser.parseString(raw).getAsJsonObject().get("msg").getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String aesEncrypt(String plain, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return hex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String aesDecrypt(String cipherHex, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return new String(cipher.doFinal(fromHex(cipherHex)), StandardCharsets.UTF_8);
    }

    private static String rsaEncrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey());
        return Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String rsaDecrypt(String encoded) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey());
        return new String(cipher.doFinal(Base64.decode(encoded, Base64.DEFAULT)), StandardCharsets.UTF_8);
    }

    private static PublicKey publicKey() throws Exception {
        byte[] bytes = Base64.decode(RSA_PUBLIC_KEY, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }

    private static PrivateKey privateKey() throws Exception {
        byte[] bytes = Base64.decode(RSA_PRIVATE_KEY, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static String randomText(int length) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return result.toString();
    }

    private static String md5Hex(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return hex(digest);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
        return result.toString();
    }

    private static byte[] fromHex(String value) {
        if (value == null || (value.length() & 1) != 0) return new byte[0];
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return result;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
