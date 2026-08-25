package com.lionray.vpn.util

import android.content.Context

/**
 * User-selectable routing mode & DNS preset, persisted in SharedPreferences.
 */
object SettingsStore {

    private const val PREF = "lionray_settings"
    private const val KEY_ROUTING = "routing_mode"
    private const val KEY_DNS = "dns_preset"
    private const val KEY_LANG_CHOSEN = "lang_chosen"
    const val LANG_EN = "en"
    const val LANG_MY = "my"

    fun isLanguageChosen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANG_CHOSEN, false)

    fun setLanguageChosen(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LANG_CHOSEN, true).apply()
    }

    /** Battery-optimization prompt: ask exactly once, never nag again. */
    fun hasPromptedBattery(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("battery_prompted", false)

    fun setBatteryPrompted(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("battery_prompted", true).apply()
    }

    /** Auto-reconnect the tunnel when the underlying network changes (WiFi<->SIM). */
    fun autoReconnect(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("auto_reconnect", true)

    fun setAutoReconnect(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_reconnect", on).apply()
    }

    /**
     * Auto-failover: when the active server dies (core exit or unreachable),
     * automatically switch to the fastest reachable alternative and reconnect.
     */
    fun autoFailover(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("auto_failover", true)

    fun setAutoFailover(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_failover", on).apply()
    }

    /** DNS/routing-level ad & tracker blocking. */
    fun adBlock(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("ad_block", true)

    fun setAdBlock(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("ad_block", on).apply()
    }

    /**
     * User-defined domains that must BYPASS the proxy (go direct with the
     * phone's own IP). One domain per line or comma-separated.
     * Useful when sites block Cloudflare/proxy exit IPs.
     */
    fun bypassRaw(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("bypass_domains", "") ?: ""

    fun setBypassRaw(ctx: Context, raw: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("bypass_domains", raw).apply()
    }

    /** Parsed bypass entries with xray "domain:" suffix-match prefix. */
    fun bypassDomains(ctx: Context): List<String> =
        bypassRaw(ctx).split('\n', ',')
            .map { it.trim().lowercase().removePrefix("domain:") }
            .filter { it.length > 3 && !it.startsWith("#") && it.contains('.') }
            .distinct()
            .map { "domain:$it" }

    /**
     * true => VoIP call media is tunneled via XUDP. DEFAULT TRUE because in
     * censored networks (MM/CN) ISPs drop direct UDP to Meta media servers
     * after a few seconds — the classic "call connects then endless spinner".
     */
    fun voipViaVpn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("voip_via_vpn", true)

    fun setVoipViaVpn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("voip_via_vpn", on).apply()
    }

    fun language(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("app_lang", LANG_EN) ?: LANG_EN

    fun setLanguage(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("app_lang", code).apply()
    }

    fun periodicAutoPing(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("auto_ping_periodic", false)

    fun setPeriodicAutoPing(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_ping_periodic", on).apply()
    }

    // ---------------- per-app VPN bypass ----------------

    fun bypassApps(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet("bypass_apps", emptySet()).orEmpty()

    fun toggleBypassApp(ctx: Context, pkg: String): Boolean {
        val cur = bypassApps(ctx).toMutableSet()
        val added = if (cur.contains(pkg)) {
            cur.remove(pkg); false
        } else {
            cur.add(pkg); true
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet("bypass_apps", cur).apply()
        return added
    }

    /** In-process mirror of the bypass list, kept in sync by the picker UI. */
    val bypassAppSet: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun initBypassMirror(ctx: Context) {
        bypassAppSet.clear()
        bypassAppSet.addAll(bypassApps(ctx))
    }

    // ---------------- routing modes ----------------
    const val MODE_GLOBAL = "global"        // everything through the proxy
    // Legacy modes kept as constants for compile compat (routing is Global-only now)
    internal const val MODE_SPLIT_CN = "split_cn"
    internal const val MODE_DIRECT = "direct_all"

    fun routingMode(ctx: Context): String = MODE_GLOBAL

    fun setRoutingMode(ctx: Context, v: String) {
        // Global-only: ignore any other value
    }

    // ---------------- DNS presets ----------------
    data class Dns(
        val key: String,
        val label: String,
        val servers: List<String>,
        /** true => resolve domestically (packets leave outside the tunnel) */
        val domestic: Boolean
    )

    fun dnsPresets(): List<Dns> = listOf(
        Dns("auto", "Auto (Cloudflare + Google)",
            listOf("1.1.1.1", "8.8.8.8"), domestic = false),
        Dns("cloudflare", "Cloudflare (1.1.1.1)",
            listOf("1.1.1.1", "1.0.0.1"), domestic = false),
        Dns("google", "Google (8.8.8.8)",
            listOf("8.8.8.8", "8.8.4.4"), domestic = false),
        Dns("alidns", "AliDNS China (223.5.5.5)",
            listOf("223.5.5.5", "223.6.6.6"), domestic = true),
        Dns("dnspod", "Tencent DNSPod China (119.29.29.29)",
            listOf("119.29.29.29", "182.254.116.116"), domestic = true),
        Dns("opendns", "OpenDNS",
            listOf("208.67.222.222", "208.67.220.220"), domestic = false)
    )

    fun dnsPresetKey(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_DNS, "auto") ?: "auto"

    fun setDnsPreset(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_DNS, key).apply()
    }

    fun currentDns(ctx: Context): Dns =
        dnsPresets().firstOrNull { it.key == dnsPresetKey(ctx) } ?: dnsPresets().first()

    // ---------------- core update notification ----------------

    fun coreUpdateAvailable(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("core_update_available", false)

    fun setCoreUpdateAvailable(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("core_update_available", on).commit()
    }

    fun coreUpdateLatestVersion(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("core_update_latest", "") ?: ""

    fun setCoreUpdateLatestVersion(ctx: Context, v: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("core_update_latest", v).commit()
    }

    /** Banner dismissed for this session (re-shows on next app start). */
    fun coreUpdateDismissed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("core_update_dismissed", false)

    fun setCoreUpdateDismissed(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("core_update_dismissed", true).commit()
    }

    /** Call after a successful core update or when user opens the app
     *  with no newer version available. Uses commit() (synchronous) to
     *  guarantee the write survives an imminent Process.killProcess(). */
    fun clearCoreUpdateState(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove("core_update_available")
            .remove("core_update_latest")
            .remove("core_update_dismissed")
            .commit()
    }

    // ---------------- APK update notification (Android 10+) ----------------

    fun apkUpdateAvailable(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("apk_update_available", false)

    fun setApkUpdateAvailable(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("apk_update_available", on).commit()
    }

    fun apkUpdateLatestVersion(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("apk_update_latest", "") ?: ""

    fun setApkUpdateLatestVersion(ctx: Context, v: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("apk_update_latest", v).commit()
    }

    fun apkUpdateDismissed(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("apk_update_dismissed", false)

    fun setApkUpdateDismissed(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("apk_update_dismissed", true).commit()
    }

    fun apkUpdateDownloadUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("apk_update_download_url", "") ?: ""

    fun setApkUpdateDownloadUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("apk_update_download_url", url).commit()
    }

    fun clearApkUpdateState(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove("apk_update_available")
            .remove("apk_update_latest")
            .remove("apk_update_dismissed")
            .remove("apk_update_download_url")
            .commit()
    }
}
