package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.catvod.net.OkHttp
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Android 端 GitHub Release 更新器。
 *
 * <p>Release 中只选择当前架构的正式 APK，明确排除 unsigned/debug 包；下载完成后校验
 * Content-Length、GitHub digest（如果 Release 提供）和 APK ZIP 头，避免把错误页面或未签名
 * 构建产物交给系统安装器。安装仍由系统 Package Installer 执行，因此会继续校验证书，
 * 不会静默覆盖不同签名的应用。
 */
object AppUpdateManager {
    private const val repository = "JackEric6/cc-tv-android"
    private const val latestReleaseUrl =
        "https://api.github.com/repos/$repository/releases/latest"
    private const val userAgent = "cc-tv-android-update"
    private const val apkMimeType = "application/vnd.android.package-archive"
    private val githubAccelPrefixes = listOf(
        "https://gh-proxy.org/",
        "https://gh.acmsz.top/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh.ddlc.top/",
        "https://gh.monlor.com/",
        "https://github.ikgy.top/",
        "https://ghproxy.imciel.com/",
        "https://gh.catmak.name/",
        "https://githubdog.com/",
        "https://git.yylx.win/",
        "https://ghproxy.cxkpro.top/",
    )

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val notes: String,
        val assetName: String,
        val assetUrl: String,
        val assetSize: Long,
        val sha256: String?,
    )

    data class DownloadProgress(val downloaded: Long, val total: Long)

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val currentVersion = packageVersion(context)
        val release = requestLatestRelease(currentVersion)
        val latestVersion = normalizeVersion(release.optString("tag_name"))
            ?: throw IOException("Release 版本号无效")
        if (compareVersions(latestVersion, normalizeVersion(currentVersion) ?: currentVersion) <= 0) {
            return@withContext null
        }
        val asset = selectApkAsset(release)
            ?: throw IOException("最新 Release 没有当前设备可安装的正式 APK")
        UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            notes = release.optString("body").trim(),
            assetName = asset.optString("name"),
            assetUrl = asset.optString("browser_download_url"),
            assetSize = asset.optLong("size", -1L),
            sha256 = asset.optString("digest")
                .removePrefix("sha256:")
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) },
        )
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(updateDir, safeName.ifEmpty { "cc-tv-${info.latestVersion}.apk" })
        val temporary = File(target.parentFile, "${target.name}.part")
        if (target.exists()) target.delete()
        if (temporary.exists()) temporary.delete()

        var lastError: IOException? = null
        for (url in downloadUrls(info.assetUrl)) {
            try {
                downloadOnce(url, info, temporary, onProgress)
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("保存 APK 失败")
                return@withContext target
            } catch (error: Exception) {
                temporary.delete()
                lastError = error as? IOException ?: IOException(error.message ?: "APK 下载失败", error)
            }
        }
        throw IOException("所有 GitHub 下载线路均失败：${lastError?.message ?: "未知错误"}")
    }

    private fun downloadOnce(
        url: String,
        info: UpdateInfo,
        temporary: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "$userAgent/${info.currentVersion}")
            .get()
            .build()
        OkHttp.client(60_000L).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val expectedSize = info.assetSize.takeIf { it > 0L } ?: body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReported = 0L
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (downloaded == expectedSize || downloaded - lastReported >= 256L * 1024L) {
                            lastReported = downloaded
                            onProgress(DownloadProgress(downloaded, expectedSize))
                        }
                    }
                }
            }
            onProgress(DownloadProgress(downloaded, expectedSize))
            if (expectedSize > 0L && downloaded != expectedSize) {
                throw IOException("APK 大小校验失败：$downloaded/$expectedSize")
            }
            val actualDigest = digest.digest().toHex()
            if (info.sha256 != null && !actualDigest.equals(info.sha256, ignoreCase = true)) {
                throw IOException("APK SHA-256 校验失败")
            }
            if (!hasZipHeader(temporary)) throw IOException("下载内容不是有效 APK")
        }
    }

    private fun requestLatestRelease(currentVersion: String): JSONObject {
        var lastError: IOException? = null
        val urls = githubAccelPrefixes.map { "$it$latestReleaseUrl" } + latestReleaseUrl
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "$userAgent/$currentVersion")
                    .get()
                    .build()
                OkHttp.client(8_000L).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val release = JSONObject(response.body.string())
                    if (release.optString("tag_name").isBlank()) throw IOException("Release 响应缺少版本标签")
                    return release
                }
            } catch (error: Exception) {
                lastError = error as? IOException ?: IOException(error.message ?: "请求失败", error)
            }
        }
        throw IOException("GitHub Release 请求失败：${lastError?.message ?: "所有线路均失败"}")
    }

    private fun downloadUrls(originalUrl: String): List<String> =
        githubAccelPrefixes.map { "$it$originalUrl" } + originalUrl

    /**
     * 启动系统安装器。返回 false 表示系统要求用户先允许本应用安装未知来源。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) throw IOException("APK 文件不存在")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, apkMimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return true
    }

    fun packageVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }

    private fun selectApkAsset(release: JSONObject): JSONObject? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase(Locale.ROOT)
        val assets = release.optJSONArray("assets") ?: return null
        val candidates = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { asset ->
                val name = asset.optString("name").lowercase(Locale.ROOT)
                val url = asset.optString("browser_download_url")
                name.endsWith(".apk") &&
                    url.startsWith("https://") &&
                    !name.contains("unsigned") &&
                    !name.contains("debug")
            }
        return candidates.minByOrNull { asset ->
            val name = asset.optString("name").lowercase(Locale.ROOT)
            when {
                name.contains(abi) -> 0
                abi == "arm64-v8a" && name.contains("arm64") -> 1
                name.contains("universal") -> 2
                else -> 3
            }
        }
    }

    private fun normalizeVersion(value: String): String? {
        val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(value.trim()) ?: return null
        return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = normalizeVersion(left)?.split(".")?.map(String::toInt) ?: return 0
        val b = normalizeVersion(right)?.split(".")?.map(String::toInt) ?: return 0
        for (index in 0..2) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return 0
    }

    private fun hasZipHeader(file: File): Boolean {
        file.inputStream().use { input ->
            return input.read() == 'P'.code &&
                input.read() == 'K'.code &&
                input.read() == 3 &&
                input.read() == 4
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
