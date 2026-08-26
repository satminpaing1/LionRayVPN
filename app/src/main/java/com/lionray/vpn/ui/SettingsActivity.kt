package com.lionray.vpn.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lionray.vpn.R
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.util.PingEngine
import com.lionray.vpn.util.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var rgRouting: RadioGroup
    private lateinit var rgDns: RadioGroup
    private lateinit var rgLanguage: RadioGroup

    @Volatile private var cachedInstalledVersion: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) { "" }
        findViewById<android.widget.TextView>(R.id.tvAboutVersion).text =
            getString(R.string.about_version, versionName)

        setupRouting()
        setupDns()
        setupLanguage()
        setupAutoPing()
        setupAutoReconnect()
        setupAutoFailover()
        setupVoipVpn()
        setupAdBlock()
        setupBypassDomains()
        setupAppRules()
        setupBattery()
        setupUpdateCore()
        setupDeleteAll()
        setupAbout()
        setupNav()

        recheckCoreUpdate()
    }

    override fun onResume() {
        super.onResume()

        val currentCode = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (_: Throwable) { 0 }

        if (Build.VERSION.SDK_INT >= 29) {
            val latest = SettingsStore.apkUpdateLatestVersion(this)
            if (latest.isNotEmpty() && currentCode > 0) {
                val latestCode = try {
                    val m = Regex("""(\d+)\.(\d+)""").find(latest)
                    if (m != null) { val (a, b) = m.destructured; a.toInt() * 10000 + b.toInt() * 100 }
                    else 0
                } catch (_: Throwable) { 0 }
                if (currentCode >= latestCode && latestCode > 0) {
                    SettingsStore.clearApkUpdateState(this)
                }
            }
        }

        recheckCoreUpdate()
    }

    private fun recheckCoreUpdate() {
        Thread {
            val installed = com.lionray.vpn.core.XrayBridge.version()
            cachedInstalledVersion = installed
            val latest = runCatching { com.lionray.vpn.util.VersionChecker.latestXray() }.getOrNull()
            if (installed != "unknown" && latest != null &&
                com.lionray.vpn.util.VersionChecker.isNewer(installed, latest)
            ) {
                SettingsStore.setCoreUpdateAvailable(this, true)
                SettingsStore.setCoreUpdateLatestVersion(this, latest)
            } else {
                SettingsStore.clearCoreUpdateState(this)
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    setupUpdateCore()
                    setupAbout()
                }
            }
        }.start()
    }

    private fun setupUpdateCore() {
        val btn = findViewById<android.widget.TextView>(R.id.btnUpdateCore)
        val panel = findViewById<android.view.View>(R.id.coreProgressPanel)
        val bar =
            findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
                R.id.pbCore
            )
        val pct = findViewById<android.widget.TextView>(R.id.tvCorePercent)
        val dot = findViewById<android.view.View>(R.id.viewUpdateDot)

        val installedVer = cachedInstalledVersion.ifEmpty { "…" }

        val hasUpdate: Boolean
        val latestVer: String

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            hasUpdate = SettingsStore.apkUpdateAvailable(this)
            latestVer = SettingsStore.apkUpdateLatestVersion(this)
        } else {
            hasUpdate = SettingsStore.coreUpdateAvailable(this)
            latestVer = SettingsStore.coreUpdateLatestVersion(this)
        }

        if (hasUpdate) {
            dot.visibility = android.view.View.VISIBLE
            btn.text = if (android.os.Build.VERSION.SDK_INT >= 29)
                getString(R.string.update_core_title)
            else getString(R.string.update_core_title)
        } else {
            dot.visibility = android.view.View.GONE
            btn.text = getString(R.string.xray_core_version, installedVer) + "  ✓"
        }

        fun progress(p: Int) {
            if (isDestroyed || isFinishing) return
            if (p < 0) {
                bar.isIndeterminate = true
                pct.text = "…"
            } else {
                bar.isIndeterminate = false
                bar.setProgressCompat(p, true)
                pct.text = "$p%"
            }
        }

        btn.setOnClickListener {
            if (!hasUpdate) {
                toast(getString(R.string.xray_core_version, installedVer))
                return@setOnClickListener
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                btn.isEnabled = false
                panel.visibility = android.view.View.VISIBLE
                progress(-1)
                val storedUrl = SettingsStore.apkUpdateDownloadUrl(this)
                Thread {
                    val result = runCatching {
                        val url = storedUrl.ifEmpty {
                            com.lionray.vpn.util.ApkUpdater.fetchLatestApk()?.downloadUrl
                                ?: throw Exception("could not find APK on GitHub")
                        }
                        com.lionray.vpn.util.ApkUpdater.downloadAndInstall(
                            this, url
                        ) { p -> runOnUiThread { progress(p) } }
                    }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        btn.isEnabled = true
                        panel.visibility = android.view.View.GONE
                        result.fold(
                            onSuccess = { file ->
                                com.lionray.vpn.util.ApkUpdater.installApk(this, file)
                                toast(R.string.apk_downloaded)
                            },
                            onFailure = { toast(getString(R.string.apk_update_fail, it.message ?: "")) }
                        )
                    }
                }.start()
                return@setOnClickListener
            }
            btn.isEnabled = false
            panel.visibility = android.view.View.VISIBLE
            progress(-1)
            Thread {
                val result = runCatching {
                    com.lionray.vpn.util.CoreUpdater.downloadAndInstall(this) { p ->
                        runOnUiThread { progress(p) }
                    }
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    btn.isEnabled = true
                    panel.visibility = android.view.View.GONE
                    result.fold(
                        onSuccess = {
                            findViewById<android.widget.TextView>(R.id.tvXrayVersion).text =
                                getString(R.string.xray_core_version, it.version)
                            dot.visibility = android.view.View.GONE
                            SettingsStore.clearCoreUpdateState(this@SettingsActivity)
                            btn.text = getString(R.string.xray_core_version, it.version) + "  ✓"
                            toast(getString(R.string.core_updated_ok, it.version))
                        },
                        onFailure = { toast(getString(R.string.core_update_fail, it.message ?: "")) }
                    )
                }
            }.start()
        }
    }

    private fun setupAbout() {
        val panel = findViewById<android.view.View>(R.id.aboutPanel)
        val chevron = findViewById<android.widget.TextView>(R.id.tvAboutChevron)
        val core = cachedInstalledVersion.ifEmpty { "…" }
        findViewById<android.widget.TextView>(R.id.tvXrayVersion).text =
            getString(R.string.xray_core_version, core)

        findViewById<android.view.View>(R.id.aboutHeader).setOnClickListener {
            val show = panel.visibility != android.view.View.VISIBLE
            panel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            chevron.text = if (show) "▴" else "▾"
        }

        val rowTg = findViewById<android.view.View>(R.id.rowTelegram)
        rowTg.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/lionrayvpn")))
            } catch (_: Throwable) {
                toast(R.string.err_unknown)
            }
        }
    }

    private fun setupRouting() {
        // Global-only mode — no radio group to wire up
    }

    private fun setupDns() {
        rgDns = findViewById(R.id.rgDns)
        val currentDns = SettingsStore.dnsPresetKey(this)
        for (dns in SettingsStore.dnsPresets()) {
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = dns.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            rb.tag = dns.key
            rgDns.addView(rb)
            if (dns.key == currentDns) rb.isChecked = true
        }
        rgDns.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId)
            (rb.tag as? String)?.let { SettingsStore.setDnsPreset(this, it) }
        }
    }

    private fun setupLanguage() {
        rgLanguage = findViewById(R.id.rgLanguage)
        val current = SettingsStore.language(this)
        val options = listOf(
            SettingsStore.LANG_EN to getString(R.string.lang_english),
            SettingsStore.LANG_MY to getString(R.string.lang_myanmar)
        )
        for ((code, label) in options) {
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            rb.tag = code
            rgLanguage.addView(rb)
            if (code == current) rb.isChecked = true
        }
        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId)
            val code = rb.tag as? String ?: return@setOnCheckedChangeListener
            if (code == SettingsStore.language(this)) return@setOnCheckedChangeListener
            SettingsStore.setLanguage(this, code)
            SettingsStore.setLanguageChosen(this)
            AppCompatDelegate.setApplicationLocales(
                if (code == SettingsStore.LANG_MY) LocaleListCompat.forLanguageTags("my")
                else LocaleListCompat.getEmptyLocaleList()
            )
        }
    }

    private fun setupAutoPing() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.swAutoPingPeriodic
        )
        sw.isChecked = SettingsStore.periodicAutoPing(this)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setPeriodicAutoPing(this, checked)
            PingEngine.setAuto(checked) { ProfileStore.profiles.value }
        }
        if (sw.isChecked) PingEngine.setAuto(true) { ProfileStore.profiles.value }
    }

    private fun setupAutoReconnect() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.swAutoReconnect
        )
        sw.isChecked = SettingsStore.autoReconnect(this)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setAutoReconnect(this, checked)
        }
    }

    private fun setupAutoFailover() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.swAutoFailover
        )
        sw.isChecked = SettingsStore.autoFailover(this)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setAutoFailover(this, checked)
        }
    }

    private fun setupVoipVpn() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.swVoipVpn
        )
        sw.isChecked = SettingsStore.voipViaVpn(this)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setVoipViaVpn(this, checked)
            toast(R.string.applies_next_connect)
        }
    }

    private fun setupAdBlock() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.swAdBlock
        )
        sw.isChecked = SettingsStore.adBlock(this)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setAdBlock(this, checked)
            toast(R.string.applies_next_connect)
        }
    }

    private fun setupBypassDomains() {
        findViewById<android.view.View>(R.id.btnBypassDomains).setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText(SettingsStore.bypassRaw(this@SettingsActivity))
                setMinLines(5)
                gravity = android.view.Gravity.TOP
                setPadding(dp(12), dp(12), dp(12), dp(12))
                hint = getString(R.string.bypass_hint)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bypass_title)
                .setMessage(R.string.bypass_sub)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ ->
                    SettingsStore.setBypassRaw(
                        this,
                        input.text.toString().trim()
                    )
                    if (SettingsStore.bypassDomains(this).isNotEmpty()) {
                        toast(getString(R.string.bypass_saved_count,
                            SettingsStore.bypassDomains(this).size))
                    }
                    toast(R.string.applies_next_connect)
                }
                .show()
        }
    }

    private fun setupDeleteAll() {
        findViewById<android.view.View>(R.id.btnDeleteAllServers).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_all)
                .setMessage(R.string.ask_delete_all)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    ProfileStore.clearAll()
                    toast(R.string.deleted_all_ok)
                }
                .show()
        }
    }

    private fun setupAppRules() {
        findViewById<android.view.View>(R.id.btnAppRules).setOnClickListener {
            startActivity(android.content.Intent(this, AppRulesActivity::class.java))
        }
    }

    private fun setupBattery() {
        findViewById<android.view.View>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                    toast(R.string.err_unknown)
                }
            }
        }
    }

    private fun setupNav() {
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottomNav
        )
        nav.selectedItemId = R.id.nav_settings
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { openTab(MainActivity::class.java); true }
                R.id.nav_configs -> { openTab(SubscriptionsActivity::class.java); true }
                else -> true
            }
        }
    }

    private fun openTab(cls: Class<*>) {
        // a tab switch REPLACES the current screen: the task never holds
        // more than one tab, so nothing can "fall back" behind the user
        startActivity(Intent(this, cls))
        finish()
        overridePendingTransition(0, 0)
    }

    /** Back key returns to Home like the bottom tab does. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        openTab(MainActivity::class.java)
    }

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
