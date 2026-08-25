package com.lionray.vpn.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lionray.vpn.core.HevTunnel
import com.lionray.vpn.data.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Figures out which country a server sits in:
 *  1. offline name heuristics (remark / sni / host tokens like "SG", "Japan")
 *  2. GeoIP lookup via the free ip-api.com endpoint (45 req/min limit)
 *
 * A background watcher scans the profile list every few seconds and fills
 * in ServerProfile.countryCode for unresolved entries, so flags appear in
 * the UI automatically no matter how the key was imported.
 */
object GeoResolver {

    /** token (lowercase) -> ISO country code */
    private val nameMap: Map<String, String> = mapOf(
        // asia
        "sg" to "SG", "singapore" to "SG",
        "jp" to "JP", "japan" to "JP", "tokyo" to "JP", "osaka" to "JP",
        "kr" to "KR", "korea" to "KR", "seoul" to "KR",
        "hk" to "HK", "hongkong" to "HK", "hong" to "HK", "kong" to "HK",
        "tw" to "TW", "taiwan" to "TW", "taipei" to "TW",
        "my" to "MY", "malaysia" to "MY", "kuala" to "MY",
        "th" to "TH", "thailand" to "TH", "bangkok" to "TH",
        "vn" to "VN", "vietnam" to "VN", "hanoi" to "VN", "saigon" to "VN",
        "ph" to "PH", "philippines" to "PH", "manila" to "PH",
        "id" to "ID", "indonesia" to "ID", "jakarta" to "ID",
        "india" to "IN", "mumbai" to "IN", "delhi" to "IN", "bombay" to "IN",
        "pk" to "PK", "pakistan" to "PK", "karachi" to "PK",
        "bd" to "BD", "bangladesh" to "BD", "dhaka" to "BD",
        "kazakhstan" to "KZ", "almaty" to "KZ",
        // middle east
        "uae" to "AE", "dubai" to "AE", "emirates" to "AE", "abudhabi" to "AE",
        "saudi" to "SA", "riyadh" to "SA", "arabia" to "SA",
        "turkey" to "TR", "istanbul" to "TR", "ankara" to "TR",
        "israel" to "IL", "telaviv" to "IL",
        "qatar" to "QA", "doha" to "QA",
        "bahrain" to "BH", "oman" to "OM", "kuwait" to "KW",
        // europe
        "uk" to "GB", "britain" to "GB", "england" to "GB", "london" to "GB",
        "scotland" to "GB", "wales" to "GB",
        "de" to "DE", "germany" to "DE", "deutschland" to "DE",
        "frankfurt" to "DE", "berlin" to "DE", "munich" to "DE",
        "fr" to "FR", "france" to "FR", "paris" to "FR",
        "netherlands" to "NL", "amsterdam" to "NL", "holland" to "NL",
        "spain" to "ES", "madrid" to "ES", "barcelona" to "ES",
        "italy" to "IT", "milan" to "IT", "rome" to "IT", "roma" to "IT",
        "switzerland" to "CH", "zurich" to "CH", "sweden" to "SE",
        "stockholm" to "SE", "norway" to "NO", "oslo" to "NO",
        "denmark" to "DK", "copenhagen" to "DK", "finland" to "FI",
        "helsinki" to "FI", "poland" to "PL", "warsaw" to "PL",
        "ukraine" to "UA", "kyiv" to "UA", "kiev" to "UA",
        "russia" to "RU", "moscow" to "RU", "moskva" to "RU",
        "romania" to "RO", "bucharest" to "RO", "bulgaria" to "BG",
        "hungary" to "HU", "budapest" to "HU", "czech" to "CZ",
        "prague" to "CZ", "austria" to "AT", "vienna" to "AT",
        "portugal" to "PT", "lisbon" to "PT", "ireland" to "IE",
        "dublin" to "IE", "greece" to "GR", "athens" to "GR",
        "belgium" to "BE", "brussels" to "BE",
        // americas
        "usa" to "US", "america" to "US", "american" to "US",
        "unitedstates" to "US", "newyork" to "US", "losangeles" to "US",
        "chicago" to "US", "dallas" to "US", "seattle" to "US",
        "sanjose" to "US", "ashburn" to "US", "miami" to "US",
        "canada" to "CA", "toronto" to "CA", "montreal" to "CA",
        "vancouver" to "CA", "mexico" to "MX", "brazil" to "BR",
        "saopaulo" to "BR", "argentina" to "AR", "buenosaires" to "AR",
        "chile" to "CL", "colombia" to "CO", "peru" to "PE",
        // oceania / africa
        "australia" to "AU", "sydney" to "AU", "melbourne" to "AU",
        "newzealand" to "NZ", "auckland" to "NZ",
        "southafrica" to "ZA", "johannesburg" to "ZA", "capetown" to "ZA",
        "egypt" to "EG", "cairo" to "EG", "nigeria" to "NG", "kenya" to "KE",
        "morocco" to "MA", "ghana" to "GH",
        // china (rarely proxied but complete)
        "china" to "CN", "shanghai" to "CN", "beijing" to "CN",
        "macau" to "MO", "mongolia" to "MN"
    )

    /** Two-letter tokens that are common words — require UPPERCASE form. */
    private val ambiguous2 = setOf(
        "in", "no", "it", "me", "us", "am", "be", "he", "we", "do", "so",
        "at", "as", "is", "or", "ok", "hi", "my", "by", "on", "an", "ca"
    )

    /** Offline country guess from free-text server labels. "" = unknown. */
    fun detectFromName(vararg texts: String): String {
        for (raw in texts) {
            if (raw.isBlank()) continue
            for (tok in raw.split(Regex("[^A-Za-z]+"))) {
                if (tok.isBlank()) continue
                val lower = tok.lowercase()
                val cc = nameMap[lower] ?: continue
                // short ambiguous words ("in", "us", "no") must appear
                // capitalized/uppercase to count as country codes
                if (lower.length <= 2 && ambiguous2.contains(lower) &&
                    tok != tok.uppercase()
                ) continue
                return cc
            }
        }
        return ""
    }

    // ------------------------------------------------------- background fill

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attempted = ConcurrentHashMap<Long, Long>()
    private const val RETRY_AFTER_MS = 30 * 60_000L
    private var appContext: Context? = null

    /** Called once from Application.onCreate. */
    fun startWatcher(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            while (isActive) {
                try {
                    val todo = ProfileStore.profiles.value.filter { it.countryCode.isBlank() }
                    for (p in todo) {
                        val now = System.currentTimeMillis()
                        val last = attempted[p.id] ?: 0L
                        if (now - last < RETRY_AFTER_MS) continue
                        attempted[p.id] = now

                        val byName = detectFromName(p.remark, p.sni, p.host, p.address)
                        val cc = if (byName.isNotBlank()) byName else geoIp(p.address)
                        if (!cc.isNullOrBlank()) {
                            ProfileStore.get(p.id)?.let { cur ->
                                if (cur.countryCode.isBlank()) {
                                    ProfileStore.upsert(cur.copy(countryCode = cc))
                                }
                            }
                        }
                        delay(1500) // be gentle with the free API rate limit
                    }
                } catch (_: Throwable) {
                }
                delay(10_000)
            }
        }
    }

    /** Free GeoIP services that accept hostnames too. null = unknown/offline.
     *  Two providers are tried because some ISPs block plain-HTTP endpoints:
     *  1) ip-api.com   (HTTP only, generous limits)
     *  2) ipwho.is     (HTTPS, passes censored networks better)
     */
    private fun geoIp(host: String): String? {
        if (!isOnline()) return null
        return geoIpIpApi(host) ?: geoIpWhoIs(host)
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun geoIpIpApi(host: String): String? = runCatching {
        val conn = URL("http://ip-api.com/json/$host?fields=countryCode")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "LionRayVPN/1.3")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val cc = JSONObject(body).optString("countryCode")
        if (cc.length == 2) cc else null
    }.getOrNull()

    private fun geoIpWhoIs(host: String): String? = runCatching {
        val conn = URL("https://ipwho.is/$host")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "LionRayVPN/1.3")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val cc = JSONObject(body).optString("country_code")
        if (cc.length == 2) cc else null
    }.getOrNull()

    // --------------------------------------------------- true exit detection

    /**
     * Entry-IP GeoIP lies for CDN-fronted keys: the address may hit a
     * Cloudflare edge in the US while the real server sits in SG.
     * Once the tunnel is up we can ask the core itself — opening an HTTP
     * request through the local SOCKS inbound (127.0.0.1:port) exits at the
     * REAL server, so "which country am I calling from" gives the truth.
     */
    fun queueExitProbe(profileId: Long) {
        scope.launch {
            try {
                delay(2000) // let the fresh tunnel settle first
                if (ProfileStore.get(profileId) == null) return@launch
                if (SettingsStore.routingMode(appContext ?: return@launch) !=
                    SettingsStore.MODE_GLOBAL
                ) return@launch // direct/split modes would exit at the phone
                val cc = exitCountryViaSocks() ?: return@launch
                if (cc.length == 2) {
                    ProfileStore.get(profileId)?.let { cur ->
                        if (!cur.countryCode.equals(cc, ignoreCase = true)) {
                            ProfileStore.upsert(cur.copy(countryCode = cc))
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Country seen by the internet when traffic goes THROUGH our proxy. */
    private fun exitCountryViaSocks(): String? = runCatching {
        val socks = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", HevTunnel.SOCKS_PORT)
        )
        val conn = URL("http://ip-api.com/json/?fields=countryCode")
            .openConnection(socks) as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "LionRayVPN/1.3")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(body).optString("countryCode")
    }.getOrNull()
}
