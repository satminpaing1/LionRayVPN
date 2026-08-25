package com.lionray.vpn.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the latest published Xray-core release tag from GitHub so the
 * About panel can compare it with the core bundled in this APK.
 */
object VersionChecker {

    fun latestXray(): String? = try {
        val conn = URL("https://api.github.com/repos/XTLS/Xray-core/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "LionRayVPN")
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").trim().removePrefix("v").takeIf { it.isNotEmpty() }
        } else null
    } catch (_: Throwable) {
        null
    }

    /** true when [installed] is older than [latest] (simple numeric segment compare). */
    fun isNewer(installed: String, latest: String): Boolean {
        fun parts(v: String): List<Int>? =
            Regex("""(\d+)\.(\d+)\.(\d+)""").find(v)
                ?.groupValues?.drop(1)?.map { it.toInt() }
        val a = parts(installed) ?: return false // unparsable => never nag
        val b = parts(latest) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }
}
