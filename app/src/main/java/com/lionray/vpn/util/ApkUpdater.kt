package com.lionray.vpn.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub releases for a newer APK and downloads it for manual install.
 * Used on Android 10+ where core binary self-update is blocked by SELinux.
 */
object ApkUpdater {

    private const val TAG = "LionRay/ApkUpdate"

    /** GitHub repo that hosts the APK releases. Change this to your repo. */
    const val GITHUB_OWNER = "satminpaing1"
    const val GITHUB_REPO = "LionRayVPN"

    data class ApkInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * Fetches the latest release from GitHub.
     * Returns null if no APK asset found or on network error.
     */
    fun fetchLatestApk(): ApkInfo? {
        return try {
            val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LionRayVPN")
            if (conn.responseCode != 200) return null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val tagName = json.optString("tag_name", "").trim().removePrefix("v")
            val body = json.optString("body", "")
            val assets = json.optJSONArray("assets") ?: return null

            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null || tagName.isEmpty()) return null

            val versionCode = parseVersionCode(tagName)
            if (versionCode <= 0) return null

            ApkInfo(
                versionName = tagName,
                versionCode = versionCode,
                downloadUrl = apkUrl,
                releaseNotes = body
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns true when [remote] is newer than the installed APK. */
    fun isNewer(remote: ApkInfo, ctx: Context): Boolean {
        val localCode = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Throwable) { 0 }
        return remote.versionCode > localCode
    }

    /** Downloads the APK and fires the system package installer Intent. */
    fun downloadAndInstall(ctx: Context, apkUrl: String, onProgress: (Int) -> Unit = {}): File {
        val cacheDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(cacheDir, "lionray-update.apk")

        val conn = URL(apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "LionRayVPN")
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            apkFile.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        }
        onProgress(100)
        return apkFile
    }

    fun installApk(ctx: Context, apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** Simple version code: major*10000 + minor*100 + patch */
    private fun parseVersionCode(tag: String): Int {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(tag) ?: return 0
        val (a, b, c) = m.destructured
        return a.toInt() * 10000 + b.toInt() * 100 + c.toInt()
    }
}
