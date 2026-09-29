package com.github.catvod.crawler;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

class ProtectedInitJar {

    private static final byte[] DEX_MAGIC = new byte[]{'d', 'e', 'x'};
    private static final int BUFFER_SIZE = 8192;

    private final ConcurrentHashMap<String, Boolean> jars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> dexNativeJars = new ConcurrentHashMap<>();

    void clear() {
        jars.clear();
        dexNativeJars.clear();
    }

    boolean check(String jar) {
        Boolean cached = jars.get(jar);
        if (cached != null) return cached;
        boolean result = scan(jar, App.getInstance().getPackageName());
        jars.put(jar, result);
        return result;
    }

    boolean init(Class<?> clz) {
        Object init = null;
        try {
            startRuntimeDexCapture();
            Method get = clz.getMethod("get");
            init = get.invoke(null);
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "invoke get() failed");
        }
        bindContext(clz, init);
        if (!bindDexLoader(clz, init)) return false;
        invokeNoArg(clz, "replaceCloudDiskNames");
        invokeStartGoProxy(clz);
        return true;
    }

    /** Probe-only race capture for the transient decrypted DEX before the guard deletes it. */
    private void startRuntimeDexCapture() {
        try {
            if (!App.getInstance().getPackageName().endsWith(".probe")) return;
            final File source = new File(App.getInstance().getCodeCacheDir(), "sharedb/config.db");
            File root = App.getInstance().getExternalCacheDir();
            if (root == null) root = App.getInstance().getExternalFilesDir(null);
            final File target = root == null ? null : new File(new File(root, "spider2-runtime"), "runtime-config.db");
            if (target == null) return;
            Thread capture = new Thread(() -> {
                try {
                    File dir = target.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    for (int i = 0; i < 12000; i++) {
                        if (source.isFile() && source.length() > 0 && !target.exists()) {
                            copyFile(source, target);
                            LOG.i("ProtectedInitJar captured transient runtime DEX: " + source);
                            return;
                        }
                        Thread.sleep(1L);
                    }
                } catch (Throwable ignored) {
                    LOG.d("ProtectedInitJar", "capture transient runtime DEX failed");
                }
            }, "spider2-dex-capture");
            capture.setDaemon(true);
            capture.start();
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "start runtime DEX capture failed");
        }
    }

    void observeRuntimeLoader(Class<?> clz) {
        try {
            Class<?> nativeClass = clz.getClassLoader().loadClass("com.github.catvod.spider.DexNative");
            Method getLoader = nativeClass.getMethod("getLoader", Object.class);
            Object loader = getLoader.invoke(null, App.getInstance());
            if (loader instanceof DexClassLoader) dumpRuntimeDexLoader((DexClassLoader) loader);
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "observe runtime dex loader failed: " + ignored);
        }
    }

    private void bindContext(Class<?> clz, Object init) {
        if (init == null) return;
        try {
            Field context = clz.getDeclaredField("c");
            context.setAccessible(true);
            context.set(init, App.getInstance());
            return;
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "bind field 'c' failed, try declared fields");
        }
        for (Field field : clz.getDeclaredFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) || !Context.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                field.set(init, App.getInstance());
            } catch (Throwable ignored) {
                LOG.d("ProtectedInitJar", "bind context field '" + field.getName() + "' failed");
            }
        }
    }

    private boolean bindDexLoader(Class<?> clz, Object init) {
        if (init == null) return false;
        try {
            Class<?> nativeClass = clz.getClassLoader().loadClass("com.github.catvod.spider.DexNative");
            Method getLoader = nativeClass.getMethod("getLoader", Object.class);
            Object loader = getLoader.invoke(null, App.getInstance());
            if (!(loader instanceof DexClassLoader)) return false;
            dumpRuntimeDexLoader((DexClassLoader) loader);
            boolean bound = false;
            for (Class<?> type = clz; type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !DexClassLoader.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    field.set(init, loader);
                    bound = true;
                }
            }
            return bound;
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "bind DexClassLoader failed");
        }
        return false;
    }

    /**
     * 仅用于恢复丢失源码时的运行态取证：记录保护层真正交给 ART 的 DEX 路径，
     * 并在该路径是普通文件时复制到应用专属外部目录，便于 adb 拉回分析。
     * 失败全部忽略，不参与正常加载流程。
     */
    private void dumpRuntimeDexLoader(DexClassLoader loader) {
        OutputStream report = null;
        try {
            LOG.i("ProtectedInitJar runtime dex loader observed: " + loader);
            File root = App.getInstance().getExternalCacheDir();
            if (root == null) root = App.getInstance().getExternalFilesDir(null);
            File dir = root == null ? null : new File(root, "spider2-runtime");
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            File reportFile = new File(dir, "dex-loader.txt");
            report = new FileOutputStream(reportFile, false);
            StringBuilder text = new StringBuilder();
            text.append("loader=").append(loader).append('\n');

            Field pathListField = findField(loader.getClass(), "pathList");
            if (pathListField == null) return;
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            Field dexElementsField = findField(pathList.getClass(), "dexElements");
            if (dexElementsField == null) return;
            dexElementsField.setAccessible(true);
            Object[] elements = (Object[]) dexElementsField.get(pathList);
            int index = 0;
            for (Object element : elements) {
                Field dexFileField = findField(element.getClass(), "dexFile");
                if (dexFileField == null) continue;
                dexFileField.setAccessible(true);
                Object value = dexFileField.get(element);
                if (!(value instanceof DexFile)) continue;
                DexFile dexFile = (DexFile) value;
                String name = dexFile.getName();
                text.append("dex[").append(index++).append("]=").append(name).append('\n');
                dumpDexClasses(dexFile, text);
                File source = new File(name);
                text.append("source.exists=").append(source.exists())
                        .append(" source.isFile=").append(source.isFile())
                        .append(" source.length=").append(source.length()).append('\n');
                File copy = new File(dir, "runtime-" + index + ".dex");
                copyFile(source, copy);
                text.append("copy.exists=").append(copy.exists())
                        .append(" copy.length=").append(copy.length()).append('\n');
            }
            report.write(text.toString().getBytes("UTF-8"));
            report.flush();
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "dump runtime dex loader failed: " + ignored);
        } finally {
            close(report);
        }
    }

    private static void dumpDexClasses(DexFile dexFile, StringBuilder text) {
        try {
            Enumeration<String> entries = dexFile.entries();
            int count = 0;
            while (entries.hasMoreElements() && count < 20000) {
                String name = entries.nextElement();
                if (name.startsWith("com.github.catvod.spider.")) {
                    text.append("class=").append(name).append('\n');
                }
                count++;
            }
        } catch (Throwable ignored) {
            text.append("class-enumeration=failed\n");
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue with the superclass.
            }
        }
        return null;
    }

    private static void copyFile(File source, File target) {
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            output.flush();
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "copy runtime dex failed");
        } finally {
            close(input);
            close(output);
        }
    }

    private void invokeNoArg(Class<?> clz, String methodName) {
        try {
            Method method = clz.getMethod(methodName);
            method.invoke(null);
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "invoke '" + methodName + "' failed");
        }
    }

    private void invokeStartGoProxy(Class<?> clz) {
        try {
            Method method = clz.getMethod("startGoProxy", Context.class);
            method.invoke(null, App.getInstance());
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "invoke startGoProxy failed");
        }
    }

    private static boolean scan(String jar, String packageName) {
        try {
            File file = new File(jar);
            if (!file.exists()) return false;
            if (isDexFile(file)) {
                InputStream is = null;
                try {
                    is = new FileInputStream(file);
                    return checkDex(readBytes(is), packageName);
                } finally {
                    close(is);
                }
            }
            ZipFile zip = null;
            try {
                zip = new ZipFile(file);
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".dex")) continue;
                    InputStream is = null;
                    try {
                        is = zip.getInputStream(entry);
                        if (checkDex(readBytes(is), packageName)) return true;
                    } finally {
                        close(is);
                    }
                }
            } finally {
                close(zip);
            }
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "scan jar for protected dex failed");
        }
        return false;
    }

    private static boolean checkDex(byte[] data, String packageName) {
        if (data == null || data.length < 112 || !startsWith(data, DEX_MAGIC)) return false;
        try {
            return new Dex(data, packageName).isProtected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 2026-09-14:壳协议 jar 必然定义/引用 DexNative;与 check(杀进程风险)正交使用 */
    boolean hasDexNative(String jar) {
        Boolean cached = dexNativeJars.get(jar);
        if (cached != null) return cached;
        boolean result = scanDexNative(jar);
        dexNativeJars.put(jar, result);
        return result;
    }

    private boolean scanDexNative(String jar) {
        try {
            File file = new File(jar);
            if (!file.exists()) return false;
            if (isDexFile(file)) {
                InputStream is = null;
                try {
                    is = new FileInputStream(file);
                    return containsDexNative(readBytes(is));
                } finally {
                    close(is);
                }
            }
            ZipFile zip = null;
            try {
                zip = new ZipFile(file);
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".dex")) continue;
                    InputStream is = null;
                    try {
                        is = zip.getInputStream(entry);
                        if (containsDexNative(readBytes(is))) return true;
                    } finally {
                        close(is);
                    }
                }
            } finally {
                close(zip);
            }
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "scan dex native failed");
        }
        return false;
    }

    /** dex string pool 里类型描述符连续存储,ISO-8859-1 无损映射后字节级搜索即可,无误报 */
    private static boolean containsDexNative(byte[] data) {
        return new String(data, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("Lcom/github/catvod/spider/DexNative;");
    }

    private static boolean isDexFile(File file) {
        InputStream is = null;
        try {
            is = new FileInputStream(file);
            byte[] magic = new byte[DEX_MAGIC.length];
            int len = is.read(magic);
            return len == DEX_MAGIC.length && startsWith(magic, DEX_MAGIC);
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(is);
        }
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int len;
        while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
        close(is);
        return baos.toByteArray();
    }

    private static boolean startsWith(byte[] data, byte[] pattern) {
        if (data == null || pattern == null || data.length < pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[i] != pattern[i]) return false;
        }
        return true;
    }

    private static void close(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
            LOG.d("ProtectedInitJar", "close failed");
        }
    }

    private static class Dex {

        private final byte[] data;
        private final int stringIdsSize;
        private final int stringIdsOff;
        private final int typeIdsSize;
        private final int typeIdsOff;
        private final int methodIdsSize;
        private final int methodIdsOff;
        private final int classDefsSize;
        private final int classDefsOff;
        private final Whitelist whitelist;

        Dex(byte[] data, String packageName) {
            this.data = data;
            this.stringIdsSize = uint(0x38);
            this.stringIdsOff = uint(0x3c);
            this.typeIdsSize = uint(0x40);
            this.typeIdsOff = uint(0x44);
            this.methodIdsSize = uint(0x58);
            this.methodIdsOff = uint(0x5c);
            this.classDefsSize = uint(0x60);
            this.classDefsOff = uint(0x64);
            this.whitelist = new Whitelist(packageName);
        }

        boolean isProtected() {
            boolean hasInit = false;
            boolean hasKillProcess = false;
            boolean checkedWhitelist = false;
            for (int i = 0; i < classDefsSize; i++) {
                int off = classDefsOff + i * 32;
                String desc = typeDesc(uint(off));
                if (!desc.startsWith("Lcom/github/catvod/spider/Init")) continue;
                hasInit = hasInit || "Lcom/github/catvod/spider/Init;".equals(desc);
                int classDataOff = uint(off + 24);
                if (classDataOff <= 0) continue;
                if ("Lcom/github/catvod/spider/Init;".equals(desc)) {
                    checkedWhitelist = true;
                    if (scanInitWhitelist(classDataOff)) return false;
                }
                hasKillProcess = hasKillProcess || scanKillProcess(classDataOff);
                if (hasInit && hasKillProcess && checkedWhitelist) return true;
            }
            return hasInit && hasKillProcess;
        }

        private boolean scanInitWhitelist(int off) {
            int[] cursor = new int[]{off};
            int staticFields = uleb(cursor);
            int instanceFields = uleb(cursor);
            int directMethods = uleb(cursor);
            int virtualMethods = uleb(cursor);
            skipFields(cursor, staticFields + instanceFields);
            return scanInitMethods(cursor, directMethods) || scanInitMethods(cursor, virtualMethods);
        }

        private boolean scanInitMethods(int[] cursor, int count) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                methodIdx += uleb(cursor);
                uleb(cursor);
                int codeOff = uleb(cursor);
                if (codeOff <= 0 || methodIdx >= methodIdsSize) continue;
                if ("init".equals(methodName(methodIdx)) && scanWhitelist(codeOff)) return true;
            }
            return false;
        }

        private boolean scanKillProcess(int off) {
            int[] cursor = new int[]{off};
            int staticFields = uleb(cursor);
            int instanceFields = uleb(cursor);
            int directMethods = uleb(cursor);
            int virtualMethods = uleb(cursor);
            skipFields(cursor, staticFields + instanceFields);
            return scanKillMethods(cursor, directMethods) || scanKillMethods(cursor, virtualMethods);
        }

        private boolean scanKillMethods(int[] cursor, int count) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                methodIdx += uleb(cursor);
                uleb(cursor);
                int codeOff = uleb(cursor);
                if (codeOff > 0 && scanKillProcessCode(codeOff)) return true;
            }
            return false;
        }

        private boolean scanWhitelist(int codeOff) {
            int insnsSize = uint(codeOff + 12);
            int insnsOff = codeOff + 16;
            int end = insnsOff + insnsSize * 2;
            for (int off = insnsOff; off + 1 < end && off + 1 < data.length; off += 2) {
                int op = data[off] & 0xff;
                if (op == 0x1a && off + 3 < end) {
                    if (whitelist.contains(string(ushort(off + 2)))) return true;
                } else if (op == 0x1b && off + 5 < end) {
                    if (whitelist.contains(string(uint(off + 2)))) return true;
                }
            }
            return false;
        }

        private boolean scanKillProcessCode(int codeOff) {
            int insnsSize = uint(codeOff + 12);
            int insnsOff = codeOff + 16;
            int end = insnsOff + insnsSize * 2;
            for (int off = insnsOff; off + 3 < end && off + 3 < data.length; off += 2) {
                int op = data[off] & 0xff;
                if (op >= 0x6e && op <= 0x72 && isKillProcess(ushort(off + 2))) return true;
            }
            return false;
        }

        private void skipFields(int[] cursor, int count) {
            for (int i = 0; i < count; i++) {
                uleb(cursor);
                uleb(cursor);
            }
        }

        private boolean isKillProcess(int methodIdx) {
            if (methodIdx < 0 || methodIdx >= methodIdsSize) return false;
            return "Landroid/os/Process;".equals(methodClass(methodIdx)) && "killProcess".equals(methodName(methodIdx));
        }

        private String methodClass(int methodIdx) {
            return typeDesc(ushort(methodIdsOff + methodIdx * 8));
        }

        private String methodName(int methodIdx) {
            return string(uint(methodIdsOff + methodIdx * 8 + 4));
        }

        private String typeDesc(int typeIdx) {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return "";
            return string(uint(typeIdsOff + typeIdx * 4));
        }

        private String string(int stringIdx) {
            if (stringIdx < 0 || stringIdx >= stringIdsSize) return "";
            int[] cursor = new int[]{uint(stringIdsOff + stringIdx * 4)};
            uleb(cursor);
            int start = cursor[0];
            int end = start;
            while (end < data.length && data[end] != 0) end++;
            try {
                return new String(data, start, end - start, "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }

        private int uleb(int[] cursor) {
            int result = 0;
            int shift = 0;
            while (cursor[0] < data.length) {
                int b = data[cursor[0]++] & 0xff;
                result |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        private int ushort(int off) {
            if (off < 0 || off + 1 >= data.length) return 0;
            return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
        }

        private int uint(int off) {
            if (off < 0 || off + 3 >= data.length) return 0;
            return (data[off] & 0xff)
                    | ((data[off + 1] & 0xff) << 8)
                    | ((data[off + 2] & 0xff) << 16)
                    | ((data[off + 3] & 0xff) << 24);
        }
    }

    private static class Whitelist {

        private static final String AES_KEY = "1234123412341234";
        private final String packageName;

        Whitelist(String packageName) {
            this.packageName = packageName;
        }

        boolean contains(String value) {
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(value)) return false;
            if (packageName.equals(value) || containsItem(value)) return true;
            return containsItem(decrypt(value));
        }

        private boolean containsItem(String text) {
            if (TextUtils.isEmpty(text)) return false;
            int start = 0;
            while (start <= text.length()) {
                int end = text.indexOf(',', start);
                if (end < 0) end = text.length();
                if (packageName.equals(text.substring(start, end).trim())) return true;
                start = end + 1;
            }
            return false;
        }

        private String decrypt(String value) {
            if (!isCipherText(value)) return "";
            try {
                byte[] key = AES_KEY.getBytes("UTF-8");
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
                return new String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }

        private boolean isCipherText(String value) {
            int len = value.length();
            if (len < 24 || len % 4 != 0) return false;
            int padding = 0;
            for (int i = 0; i < len; i++) {
                char c = value.charAt(i);
                if (c == '=') {
                    padding++;
                    if (i < len - 2) return false;
                } else if (!isBase64(c)) {
                    return false;
                }
            }
            int rawLen = len * 3 / 4 - padding;
            return rawLen > 0 && rawLen % 16 == 0;
        }

        private boolean isBase64(char c) {
            return (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+'
                    || c == '/';
        }
    }
}
