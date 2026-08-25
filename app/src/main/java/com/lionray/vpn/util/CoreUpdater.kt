package com.lionray.vpn.util

import android.content.Context
import android.os.Build
import com.lionray.vpn.core.XrayBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the latest official Xray-core (android arm64) release from GitHub.
 *
 * Android 10+ (targetSdk 29+): SELinux blocks both exec-from-filesDir AND
 * write-to-nativeLibraryDir, so core self-update is not possible.  The caller
 * should surface an error message directing the user to install a new APK.
 *
 * Android 9 and below: writes to filesDir/xray/xray as before.
 */
object CoreUpdater {

    data class Result(val version: String)

    fun assetUrl(tag: String): String =
        "https://github.com/XTLS/Xray-core/releases/download/v$tag/Xray-android-arm64-v8a.zip"

    fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit = {}): Result {
        val ctx = context.applicationContext
        val tag = VersionChecker.latestXray()
            ?: throw Exception("could not read latest version from GitHub")

        if (Build.VERSION.SDK_INT >= 29) {
            throw Exception("core_update_restricted")
        }

        val zipFile = File(ctx.cacheDir, "xray-core.zip")
        try {
            val conn = URL(assetUrl(tag)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "LionRayVPN")
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zipFile.outputStream().use { output ->
                    if (total > 0) {
                        val buf = ByteArray(1 shl 16)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    } else {
                        onProgress(-1)
                        input.copyTo(output, 1 shl 16)
                        onProgress(100)
                    }
                }
            }

            val work = XrayBridge.coreWorkDir().apply { mkdirs() }
            val finalFile = File(work, "xray")
            val outFile = File(work, "xray.new")
            runCatching { outFile.delete() }
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory && File(entry.name).name == "xray") {
                        outFile.outputStream().use { zis.copyTo(it, 1 shl 16) }
                    }
                    zis.closeEntry()
                }
            }
            if (!outFile.exists() || outFile.length() < 10_000_000L) {
                throw Exception("downloaded binary is too small or missing")
            }
            runCatching { outFile.setExecutable(true, false) }

            val v = runCatching {
                ProcessBuilder(outFile.absolutePath, "version")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().useLines { lines ->
                        lines.mapNotNull {
                            Regex("""(\d+\.\d+\.\d+)""").find(it)?.groupValues?.get(1)
                        }.firstOrNull().orEmpty()
                    }
            }.getOrDefault("")
            if (v.isEmpty()) {
                runCatching { outFile.delete() }
                throw Exception("downloaded binary failed to run")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!outFile.renameTo(finalFile)) {
                throw Exception("could not replace the installed core")
            }

            return Result(tag)
        } finally {
            runCatching { zipFile.delete() }
        }
    }
}
