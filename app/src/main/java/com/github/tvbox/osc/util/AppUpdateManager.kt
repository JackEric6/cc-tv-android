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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        // 与 Windows 更新器保持同一批优先线路；代理不一定支持 GitHub API，
        // 因此请求必须并行探测并忽略 404/403 的失效线路。
        "https://gh-proxy.org/",
        "https://gh.acmsz.top/",
        "https://xsadwsd.kdns.fr/",
        "https://ghpxy.hwinzniej.top/",
        "https://js.jiangss.shop/",
        "https://cfgh.ikgy.top/",
        "https://gh.meali.top/",
        "https://gg.z321.cc.cd/",
        "https://fastgit.cc/",
        "https://github.mxw.qzz.io/",
        "https://gh.ddlc.top/",
        "https://gh.chjina.com/",
        "https://ghf.xn--eqrr82bzpe.top/",
        "https://gh.idayer.com/",
        "https://ghm.078465.xyz/",
        "https://gitproxy.mrhjx.cn/",
        "https://gh.monlor.com/",
        "https://github.geekery.cn/",
        "https://github.ednovas.xyz/",
        "https://ghproxy.felicity.land/",
        "https://down.mxw.xx.kg/",
        "https://gh.qfmc0721.cc.cd/",
        "https://github.xxlab.tech/",
        "https://gh.jjj.gv.uy/",
        "https://gh.felicity.ac.cn/",
        "https://jiashu.1win.eu.org/",
        "https://github.tbap.top/",
        "https://gh.my-website.ccwu.cc/",
        "https://cdn.akaere.online/",
        "https://gh-proxy.com/",
        "https://tvv.tw/",
        "https://gh.07150721.xyz/",
        "https://github.dpik.top/",
        "https://g.blfrp.cn/",
        "https://gh.dpik.top/",
        "https://gh.sixyin.com/",
        "https://ghproxy.net/",
        "https://gh.noki.icu/",
        "https://ghfast.top/",
        "https://gh.catmak.name/",
        "https://gh.inkchills.cn/",
        "https://git.669966.xyz/",
        "https://ghproxy.monkeyray.net/",
        "https://ghfile.geekertao.top/",
        "https://cdn.gh-proxy.com/",
        "https://j.1lin.dpdns.org/",
        "https://github.starrlzy.cn/",
        "https://github-proxy.memory-echoes.cn/",
        "https://gh.927223.xyz/",
        "https://gh.bugdey.us.kg/",
        "https://gitproxy.127731.xyz/",
        "https://gh.b52m.cn/",
        "https://down.mxw.qzz.io/",
        "https://slink.ltd/",
        "https://github.tmby.shop/",
        "https://ghpr.cc/",
        "https://gh.tryxd.cn/",
        "https://gitproxy.click/",
        "https://github.chenc.dev/",
        "https://gh.jasonzeng.dev/",
        "https://gp.zkitefly.eu.org/",
        "https://ghproxy.1888866.xyz/",
        "https://ghp.arslantu.xyz/",
        "https://free.cn.eu.org/",
        "https://ghp.keleyaa.com/",
        "https://proxy.yaoyaoling.net/",
        "https://g.z321.cc.cd/",
        "https://gap.andyjin.website/",
        "https://gh.ruan.dpdns.org/",
        "https://github.nswrz.cn/",
        "https://gh.zhai.edu.pl/",
        "https://github-cf.947563.xyz/",
        "https://github.gohj99.site/",
        "https://githubproxy.gohj99.site/",
    )
    private const val githubApiProbeLimit = 16
    private const val githubDownloadProbeLimit = 32

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

    private suspend fun requestLatestRelease(currentVersion: String): JSONObject = coroutineScope {
        val urls = releaseApiProbeUrls()
        val results = urls.map { url ->
            async(Dispatchers.IO) {
                runCatching { requestReleaseCandidate(url, currentVersion) }
            }
        }.awaitAll()
        val releases = results.mapNotNull { it.getOrNull() }
        releases.maxWithOrNull(Comparator { left, right ->
            compareVersions(left.optString("tag_name"), right.optString("tag_name"))
        })?.let { return@coroutineScope it }

        val errors = results.mapNotNull { it.exceptionOrNull()?.message }
            .distinct()
            .take(4)
            .joinToString("；")
        throw IOException("GitHub Release 请求失败：${errors.ifEmpty { "所有线路均失败" }}")
    }

    private fun requestReleaseCandidate(url: String, currentVersion: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "$userAgent/$currentVersion")
            .get()
            .build()
        OkHttp.client(8_000L).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val release = JSONObject(body)
            if (normalizeVersion(release.optString("tag_name")) == null) {
                throw IOException("Release 响应缺少有效版本标签")
            }
            if (selectApkAsset(release) == null) {
                throw IOException("Release 没有有效 APK")
            }
            return release
        }
    }

    internal fun releaseApiProbeUrls(): List<String> =
        githubAccelPrefixes.take(githubApiProbeLimit)
            .map { proxyUrl(it, latestReleaseUrl) }
            .plus(latestReleaseUrl)
            .distinct()

    internal fun releaseDownloadUrls(originalUrl: String): List<String> =
        githubAccelPrefixes.take(githubDownloadProbeLimit)
            .map { proxyUrl(it, originalUrl) }
            .plus(originalUrl)
            .distinct()

    private fun downloadUrls(originalUrl: String): List<String> =
        releaseDownloadUrls(originalUrl)

    private fun proxyUrl(prefix: String, target: String): String =
        "${prefix.trimEnd('/')}/$target"

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

    internal fun normalizeVersion(value: String): String? {
        val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(value.trim()) ?: return null
        return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
    }

    internal fun compareVersions(left: String, right: String): Int {
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
