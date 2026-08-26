package com.lionray.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import com.lionray.vpn.LionRayApp
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lionray.vpn.R
import com.lionray.vpn.core.Speed
import com.lionray.vpn.core.VpnBus
import com.lionray.vpn.core.VpnState
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.data.SubStore
import com.lionray.vpn.data.VlessParser
import com.lionray.vpn.databinding.ActivityMainBinding
import com.lionray.vpn.service.LionRayVpnService
import com.lionray.vpn.core.HevTunnel
import com.lionray.vpn.util.ExitIpChecker
import com.lionray.vpn.util.PingEngine
import com.lionray.vpn.util.QrUtil
import com.lionray.vpn.util.SettingsStore
import com.lionray.vpn.util.SubUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var activeCard: com.lionray.vpn.databinding.ItemServerBinding
    private var timerJob: Job? = null
    private var exitIpJob: Job? = null
    private var updateDialogShowing = false

    // ------------------------------------------------------------ launchers

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
            else toast(R.string.err_vpn_denied)
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startVpnService()
        }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { smartImportOrKey(it) }
    }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) decodeQrFromGallery(uri)
        }

    // -------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        styleBrandTitle()

        activeCard = com.lionray.vpn.databinding.ItemServerBinding.inflate(layoutInflater)
        binding.layoutActiveServer.addView(activeCard.root)
        bindActiveCardListeners()
        binding.btnConnect.setOnClickListener { onConnectToggle() }
        binding.layoutExitIp.setOnClickListener { refreshExitIp() }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_configs -> { openTab(SubscriptionsActivity::class.java); true }
                R.id.nav_settings -> { openTab(SettingsActivity::class.java); true }
                else -> true
            }
        }
        binding.tvStatusDetail.setOnClickListener {
            val text = VpnBus.statusMessage.value
            if (text.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LionRay", text))
                toast(R.string.copied_ok)
            }
        }

        intent?.data?.let { handleImport(it.toString()) }
        observeFlows()
        autoUpdateSubscriptions()
        autoPingOnOpen()
        maybeAskLanguage()
        setupUpdateBanner()
    }

    /** On the very first launch, let the user pick the app language. */
    private fun maybeAskLanguage() {
        if (SettingsStore.isLanguageChosen(this)) return
        val choices = arrayOf(
            getString(R.string.lang_english),
            getString(R.string.lang_myanmar)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_choose_title)
            .setCancelable(false)
            .setItems(choices) { _, which ->
                SettingsStore.setLanguageChosen(this)
                if (which == 1) {
                    SettingsStore.setLanguage(this, SettingsStore.LANG_MY)
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags("my")
                    )
                } else {
                    SettingsStore.setLanguage(this, SettingsStore.LANG_EN)
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                }
            }
            .show()
    }

    /** Show / hide the update banner at the top of the home screen. */
    private fun setupUpdateBanner() {
        if (updateDialogShowing) return
        if (isDestroyed || isFinishing) return
        if (Build.VERSION.SDK_INT >= 29) {
            val apkAvailable = SettingsStore.apkUpdateAvailable(this)
            val apkDismissed = SettingsStore.apkUpdateDismissed(this)
            val apkLatest = SettingsStore.apkUpdateLatestVersion(this)
            if (apkAvailable && !apkDismissed && apkLatest.isNotEmpty()) {
                updateDialogShowing = true
                showApkUpdateDialog(apkLatest)
                return
            }
        } else {
            val available = SettingsStore.coreUpdateAvailable(this)
            val dismissed = SettingsStore.coreUpdateDismissed(this)
            val latest = SettingsStore.coreUpdateLatestVersion(this)
            if (available && !dismissed && latest.isNotEmpty()) {
                updateDialogShowing = true
                showCoreUpdateDialog(latest)
                return
            }
        }
    }

    private var pendingInstallFile: java.io.File? = null
    private var pendingUpdateLatest: String = ""

    private val installPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val file = pendingInstallFile
            if (file != null && file.exists()) {
                com.lionray.vpn.util.ApkUpdater.installApk(this, file)
            }
        }

    private fun launchInstall(file: java.io.File) {
        if (packageManager.canRequestPackageInstalls()) {
            com.lionray.vpn.util.ApkUpdater.installApk(this, file)
        } else {
            pendingInstallFile = file
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:$packageName")
            )
            installPermLauncher.launch(intent)
        }
    }

    private fun showApkUpdateDialog(latest: String) {
        val progressView = layoutInflater.inflate(
            R.layout.dialog_core_update, null
        )
        val tvProgress = progressView.findViewById<android.widget.TextView>(R.id.tvDialogProgress)
        val bar = progressView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.pbDialogCore)
        val spinner = progressView.findViewById<android.view.View>(R.id.spinnerDialog)

        val storedUrl = SettingsStore.apkUpdateDownloadUrl(this)
        var downloadedFile: java.io.File? = null

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.apk_update_title, latest))
            .setMessage(getString(R.string.apk_update_msg))
            .setView(progressView)
            .setNegativeButton(R.string.cancel) { d, _ ->
                SettingsStore.setApkUpdateDismissed(this)
                updateDialogShowing = false
                d.dismiss()
            }
            .setPositiveButton(R.string.update_now, null)
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener { updateDialogShowing = false }
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            tvProgress.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            spinner.visibility = View.VISIBLE
            tvProgress.text = getString(R.string.downloading_apk)

            Thread {
                val result = runCatching {
                    val url = storedUrl.ifEmpty {
                        com.lionray.vpn.util.ApkUpdater.fetchLatestApk()?.downloadUrl
                            ?: throw Exception("could not find APK on GitHub")
                    }
                    com.lionray.vpn.util.ApkUpdater.downloadAndInstall(
                        this, url
                    ) { p ->
                        runOnUiThread {
                            if (p < 0) {
                                bar.isIndeterminate = true
                                tvProgress.text = getString(R.string.downloading_apk)
                            } else {
                                bar.isIndeterminate = false
                                bar.setProgressCompat(p, true)
                                tvProgress.text = "$p%"
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    result.fold(
                        onSuccess = { file ->
                            downloadedFile = file
                            tvProgress.text = getString(R.string.apk_downloaded)
                            bar.setProgressCompat(100, true)
                            spinner.visibility = View.GONE
                            // Show "Install" button — do NOT clear update state here
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = getString(R.string.install)
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                pendingInstallFile = file
                                pendingUpdateLatest = latest
                                dialog.dismiss()
                                launchInstall(file)
                            }
                        },
                        onFailure = {
                            tvProgress.text = getString(R.string.apk_update_fail, it.message ?: "")
                            spinner.visibility = View.GONE
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = getString(R.string.retry)
                            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.ok)
                        }
                    )
                }
            }.start()
        }
    }

    private fun showCoreUpdateDialog(latest: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val msg = getString(R.string.core_update_restricted)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.core_update_banner_title, latest))
                .setMessage(msg)
                .setPositiveButton(R.string.ok) { d, _ ->
                    SettingsStore.setCoreUpdateDismissed(this)
                    d.dismiss()
                }
                .setCancelable(false)
                .show()
            return
        }

        val progressView = layoutInflater.inflate(
            R.layout.dialog_core_update, null
        )
        val tvProgress = progressView.findViewById<android.widget.TextView>(R.id.tvDialogProgress)
        val bar = progressView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.pbDialogCore)
        val spinner = progressView.findViewById<android.view.View>(R.id.spinnerDialog)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.core_update_banner_title, latest))
            .setMessage(getString(R.string.core_update_dialog_msg))
            .setView(progressView)
            .setNegativeButton(R.string.cancel) { d, _ ->
                SettingsStore.setCoreUpdateDismissed(this)
                d.dismiss()
            }
            .setPositiveButton(R.string.update_now, null)
            .setCancelable(false)
            .create()

        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            tvProgress.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            spinner.visibility = View.VISIBLE
            tvProgress.text = getString(R.string.downloading_core)

            Thread {
                val result = runCatching {
                    com.lionray.vpn.util.CoreUpdater.downloadAndInstall(this) { p ->
                        runOnUiThread {
                            if (p < 0) {
                                bar.isIndeterminate = true
                                tvProgress.text = getString(R.string.downloading_core)
                            } else {
                                bar.isIndeterminate = false
                                bar.setProgressCompat(p, true)
                                tvProgress.text = "$p%"
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    result.fold(
                        onSuccess = {
                            tvProgress.text = getString(R.string.core_updated_ok, it.version)
                            bar.setProgressCompat(100, true)
                            SettingsStore.clearCoreUpdateState(this@MainActivity)
                            dialog.dismiss()
                            restartApp()
                        },
                        onFailure = {
                            tvProgress.text = getString(R.string.core_update_fail, it.message ?: "")
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.ok)
                        }
                    )
                }
            }.start()
        }
    }

    private fun restartApp() {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Pings every key once as soon as the app is opened. */
    private fun autoPingOnOpen() {
        val list = ProfileStore.profiles.value
        if (list.isNotEmpty()) PingEngine.pingAll(list)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { handleImport(it.toString()) }
    }

    private fun styleBrandTitle() {
        // kill the default action-bar label so only the gradient brand shows
        supportActionBar?.title = ""
        val tv = findViewById<android.widget.TextView>(R.id.tvBrandTitle) ?: return
        tv.post {
            val w = tv.paint.measureText(tv.text.toString()).coerceAtLeast(1f)
            tv.paint.shader = android.graphics.LinearGradient(
                0f, 0f, w, tv.textSize,
                intArrayOf(
                    0xFF818CF8.toInt(),  // soft indigo
                    0xFF38BDF8.toInt(),  // sky
                    0xFFE879F9.toInt()   // fuchsia
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            tv.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNav.post {
            if (binding.bottomNav.selectedItemId != R.id.nav_home) {
                binding.bottomNav.selectedItemId = R.id.nav_home
            }
        }
        refreshNetworkChip()
        refreshProtectionPills()

        // If we returned from install and the new version is now running, clear update state
        val currentVersionCode = try {
            if (Build.VERSION.SDK_INT >= 28)
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Throwable) { 0 }

        val latestVersion = SettingsStore.apkUpdateLatestVersion(this)
        if (latestVersion.isNotEmpty()) {
            val latestCode = try {
                val m = Regex("""(\d+)\.(\d+)""").find(latestVersion)
                if (m != null) {
                    val (a, b) = m.destructured
                    a.toInt() * 10000 + b.toInt() * 100
                } else 0
            } catch (_: Throwable) { 0 }

            if (currentVersionCode >= latestCode && latestCode > 0) {
                SettingsStore.clearApkUpdateState(this)
                pendingInstallFile = null
            }
        }

        setupUpdateBanner()
        // Re-check after async core version check completes (network + binary exec)
        if (!LionRayApp.coreUpdateChecked) {
            binding.root.postDelayed({ setupUpdateBanner() }, 5000)
        }
    }

    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ProfileStore.profiles.collect { renderActiveCard() }
                }
                launch {
                    ProfileStore.activeId.collect {
                        renderActiveCard()
                        refreshDetail()
                    }
                }
                launch {
                    PingEngine.results.collect { renderActivePing() }
                }
                launch {
                    VpnBus.state.collect { renderState(it) }
                }
                launch {
                    VpnBus.statusMessage.collect { refreshDetail() }
                }
                launch {
                    VpnBus.speed.collect { renderSpeed(it) }
                }
                launch {
                    VpnBus.usage.collect { renderUsage(it) }
                }
                launch {
                    VpnBus.blockedCount.collect { renderBlocked(it) }
                }
            }
        }
    }

    // ------------------------------------------------- home info widgets

    private fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format("%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun renderUsage(u: com.lionray.vpn.core.Usage) {
        val row = findViewById<android.view.View>(R.id.rowUsage) ?: return
        row.visibility =
            if (VpnBus.state.value == com.lionray.vpn.core.VpnState.CONNECTED)
                android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.widget.TextView>(R.id.tvUsage)?.text =
            "⬇ ${humanBytes(u.downBytes)}   ⬆ ${humanBytes(u.upBytes)}"
    }

    private fun renderBlocked(n: Long) {
        val tv = findViewById<android.widget.TextView>(R.id.pillAdBlock) ?: return
        val on = SettingsStore.adBlock(this)
        tv.text = if (on && n > 0) "🚫 " + getString(R.string.ads_blocked_fmt, n)
                  else pillText("🚫", R.string.ad_blocker, on)
    }

    private fun pillText(icon: String, labelRes: Int, on: Boolean): String =
        "$icon ${getString(labelRes)} ${if (on) getString(R.string.pill_on) else getString(R.string.pill_off)}"

    private fun refreshNetworkChip() {
        val tv = findViewById<android.widget.TextView>(R.id.tvNetChip) ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val text = when {
                caps == null -> getString(R.string.net_none)
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                    "📶 " + getString(R.string.net_wifi)
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    val op = runCatching { tm.networkOperatorName }.getOrNull().orEmpty()
                    "📶 " + op.ifBlank { getString(R.string.net_mobile) }
                }
                else -> getString(R.string.net_other)
            }
            tv.text = text
        } catch (_: Throwable) {
            tv.text = ""
        }
    }

    private fun refreshProtectionPills() {
        renderBlocked(VpnBus.blockedCount.value)
        findViewById<android.widget.TextView>(R.id.pillAutoReconnect)?.text =
            pillText("🔄", R.string.auto_reconnect, SettingsStore.autoReconnect(this))
    }

    // -------------------------------------------------------- active card

    private fun bindActiveCardListeners() {
        activeCard.root.setOnClickListener { }
        activeCard.root.setOnLongClickListener { v ->
            ProfileStore.activeProfile()?.let { onServerLongClicked(it, v) }
            true
        }
        activeCard.btnEdit.setOnClickListener {
            ProfileStore.activeProfile()?.let { openEditor(it) }
        }
        activeCard.btnDelete.setOnClickListener {
            ProfileStore.activeProfile()?.let { confirmDelete(it) }
        }
        activeCard.btnPing.setOnClickListener {
            ProfileStore.activeProfile()?.let { p ->
                PingEngine.ping(p)
                toast(getString(R.string.pinging, 1))
            }
        }
        activeCard.btnShare.setOnClickListener {
            ProfileStore.activeProfile()?.let { showShareDialog(it) }
        }
    }

    /** Little box asking how to share: copy key, QR code or share link. */
    private fun showShareDialog(p: ServerProfile) {
        val uri = p.toShareUri()
        MaterialAlertDialogBuilder(this)
            .setTitle(p.displayName())
            .setItems(
                arrayOf(
                    getString(R.string.copy_uri),
                    getString(R.string.share_qr),
                    getString(R.string.share_link)
                )
            ) { _, which ->
                when (which) {
                    0 -> copyToClipboard(uri)
                    1 -> showQrDialog(p.displayName(), uri)
                    else -> shareLink(uri)
                }
            }
            .show()
    }

    private fun renderActiveCard() {
        val p = ProfileStore.activeProfile()
        if (p == null) {
            binding.layoutActiveServer.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.layoutActiveServer.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        val ctx = this
        activeCard.tvName.text = p.displayName()
        activeCard.tvAddr.text = p.maskedAddress()
        activeCard.tvMeta.text = buildString {
            if (!p.protocol.equals("vless", true)) {
                append(p.protocol.uppercase())
                append(" • ")
            }
            append(p.network.uppercase())
            append(" • ")
            append(p.security.uppercase())
            if (p.flow.isNotBlank()) {
                append(" • ")
                append(p.flow)
            }
        }
        val dp = (2 * resources.displayMetrics.density).toInt()
        activeCard.card.strokeWidth = dp
        activeCard.card.strokeColor = ContextCompat.getColor(ctx, R.color.brand)
        activeCard.card.setCardBackgroundColor(
            ContextCompat.getColor(ctx, R.color.surface_card_active)
        )
        renderActivePing()
    }

    private fun renderActivePing() {
        val p = ProfileStore.activeProfile() ?: return
        val ms = PingEngine.results.value[p.id]
        val ctx = this
        val color: Int
        when {
            ms == null || ms == 0 -> {
                activeCard.tvPing.text = getString(R.string.latency_none)
                color = R.color.ping_gray
            }
            ms == PingEngine.TIMEOUT_MS || ms < 0 -> {
                activeCard.tvPing.text = getString(R.string.latency_timeout)
                color = R.color.ping_red
            }
            ms < 200 -> {
                activeCard.tvPing.text = getString(R.string.latency_ms, ms)
                color = R.color.ping_green
            }
            ms < 500 -> {
                activeCard.tvPing.text = getString(R.string.latency_ms, ms)
                color = R.color.ping_amber
            }
            else -> {
                activeCard.tvPing.text = getString(R.string.latency_ms, ms)
                color = R.color.ping_red
            }
        }
        activeCard.tvPing.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
    }

    private fun renderSpeed(s: Speed) {
        val connected = VpnBus.state.value == VpnState.CONNECTED
        binding.layoutSpeed.visibility =
            if (connected) View.VISIBLE else View.GONE
        if (connected) {
            binding.tvDownSpeed.text = formatSpeed(s.downBps)
            binding.tvUpSpeed.text = formatSpeed(s.upBps)
        }
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000_000 ->
            String.format(java.util.Locale.US, "%.1f GB/s", bps / 1_000_000_000.0)
        bps >= 1_000_000 ->
            String.format(java.util.Locale.US, "%.1f MB/s", bps / 1_000_000.0)
        bps >= 1_000 ->
            String.format(java.util.Locale.US, "%.0f KB/s", bps / 1_000.0)
        else -> String.format(java.util.Locale.US, "%d B/s", bps)
    }

    // ------------------------------------------------------------------ state

    private fun renderState(state: VpnState) {
        val active = state == VpnState.CONNECTED || state == VpnState.CONNECTING
        val dotColor = when (state) {
            VpnState.CONNECTED -> ContextCompat.getColor(this, R.color.ping_green)
            VpnState.CONNECTING -> ContextCompat.getColor(this, R.color.ping_amber)
            VpnState.ERROR -> ContextCompat.getColor(this, R.color.ping_red)
            else -> ContextCompat.getColor(this, R.color.ping_gray)
        }
        binding.viewDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        binding.viewHalo.backgroundTintList = ColorStateList.valueOf(dotColor)
        binding.tvState.setText(
            when (state) {
                VpnState.CONNECTED -> R.string.status_connected
                VpnState.CONNECTING -> R.string.status_connecting
                VpnState.ERROR -> R.string.status_error
                else -> R.string.status_ready
            }
        )
        binding.btnConnect.setText(
            if (active) R.string.btn_disconnect else R.string.btn_connect
        )
        binding.btnConnect.icon = ContextCompat.getDrawable(
            this, if (active) R.drawable.ic_stop else R.drawable.ic_play
        )

        timerJob?.cancel()
        timerJob = null
        if (state == VpnState.CONNECTED) {
            startTimer()
            refreshExitIp()
        } else {
            exitIpJob?.cancel()
            binding.layoutExitIp.visibility = View.GONE
        }
        if (state == VpnState.ERROR) showErrorDialog()
        refreshDetail()
        refreshNetworkChip()
        refreshProtectionPills()
        renderUsage(VpnBus.usage.value)
        renderSpeed(VpnBus.speed.value)
    }

    private var lastErrorShown: String? = null

    private fun showErrorDialog() {
        val msg = VpnBus.statusMessage.value.ifBlank { getString(R.string.err_unknown) }
        if (msg == lastErrorShown) return
        lastErrorShown = msg
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.err_dialog_title)
            .setMessage(msg)
            .setNegativeButton(R.string.ok, null)
            .setPositiveButton(R.string.btn_copy_error) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LionRay", msg))
                toast(R.string.copied_ok)
            }
            .show()
    }

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (isActive) {
                refreshDetail()
                delay(1000)
            }
        }
    }

    /**
     * Detects the REAL public IP by querying a geo service THROUGH the
     * tunnel (local SOCKS inbound). Tap the pill to re-check.
     */
    private fun refreshExitIp() {
        if (VpnBus.state.value != VpnState.CONNECTED) return
        exitIpJob?.cancel()
        binding.layoutExitIp.visibility = View.VISIBLE
        binding.tvFlag.text = "\uD83C\uDF10"
        binding.tvExitIp.text = getString(R.string.exit_ip_checking)
        binding.tvExitCountry.text = ""
        exitIpJob = lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                ExitIpChecker.fetch(HevTunnel.SOCKS_PORT)
            }
            if (isDestroyed || isFinishing || VpnBus.state.value != VpnState.CONNECTED) return@launch
            if (info == null) {
                binding.tvExitIp.text = getString(R.string.exit_ip_fail)
            } else {
                binding.tvFlag.text = ExitIpChecker.flagEmoji(info.code)
                binding.tvExitIp.text = info.ip
                binding.tvExitCountry.text = info.country.orEmpty()
            }
        }
    }

    private fun refreshDetail() {
        val base = VpnBus.statusMessage.value
        val active = ProfileStore.activeProfile()
        binding.tvSelected.text =
            active?.displayName() ?: getString(R.string.hint_no_server)
        binding.tvStatusDetail.text = when (VpnBus.state.value) {
            VpnState.CONNECTED -> {
                val elapsed = (System.currentTimeMillis() - VpnBus.startedAtMs) / 1000
                buildString {
                    append(active?.maskedAddress().orEmpty())
                    append("\n")
                    append(getString(R.string.uptime))
                    append(" ")
                    append(formatElapsed(elapsed))
                }
            }
            VpnState.ERROR -> {
                val t = base.ifBlank { getString(R.string.err_unknown) }
                "$t\n${getString(R.string.tap_to_copy_hint)}"
            }
            else -> base
        }
    }

    private fun formatElapsed(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    // --------------------------------------------------------------- connect

    private fun onConnectToggle() {
        val state = VpnBus.state.value
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING) {
            sendStop()
            return
        }
        if (ProfileStore.activeProfile() == null) {
            toast(R.string.hint_no_server)
            return
        }
        prepareVpnThenStart()
    }

    private fun prepareVpnThenStart() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            ensureNotifPermissionThenStart()
        }
    }

    private fun ensureNotifPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        // connect straight away; battery keep-alive is opt-in via the
        // green button in Settings — nothing pops up on connect
        val i = Intent(this, LionRayVpnService::class.java)
            .setAction(LionRayVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun sendStop() {
        startService(
            Intent(this, LionRayVpnService::class.java)
                .setAction(LionRayVpnService.ACTION_STOP)
        )
    }

    private fun onServerClicked(profile: ServerProfile) {
        ProfileStore.setActive(profile.id)
        PingEngine.ping(profile)
        when (VpnBus.state.value) {
            VpnState.DISCONNECTED -> prepareVpnThenStart()
            else -> startVpnService() // hot swap to the new server
        }
    }

    private fun onServerLongClicked(profile: ServerProfile, anchor: View) {
        showShareDialog(profile)
    }

    // ------------------------------------------------------------ import (+)

    private fun showAddSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.sheet_add)
        sheet.findViewById<View>(R.id.actClipboard)?.setOnClickListener {
            sheet.dismiss(); importFromClipboard()
        }
        sheet.findViewById<View>(R.id.actScan)?.setOnClickListener {
            sheet.dismiss(); scanQr()
        }
        sheet.findViewById<View>(R.id.actGallery)?.setOnClickListener {
            sheet.dismiss()
            galleryLauncher.launch("image/*")
        }
        sheet.findViewById<View>(R.id.actManual)?.setOnClickListener {
            sheet.dismiss(); openEditor(null)
        }
        sheet.show()
    }

    private fun importFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast(R.string.clipboard_empty)
            return
        }
        smartImportOrKey(text)
    }

    /** Subscription URLs are fetched as a whole; anything else is a key. */
    private fun smartImportOrKey(raw: String) {
        lifecycleScope.launch {
            if (!SubUpdater.smartImport(this@MainActivity, raw)) handleImport(raw)
        }
    }

    private fun scanQr() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setCaptureActivity(CapturePortrait::class.java)
        qrLauncher.launch(options)
    }

    /** Picks an image from the gallery and tries to read a QR key out of it. */
    private fun decodeQrFromGallery(uri: android.net.Uri) {
        try {
            val bmp = decodeSampledBitmap(uri) ?: run {
                toast(R.string.qr_not_found); return
            }
            val text = QrUtil.readFromBitmap(bmp)
            bmp.recycle()
            if (text.isNullOrBlank()) toast(R.string.qr_not_found) else smartImportOrKey(text)
        } catch (t: Throwable) {
            toast(R.string.qr_not_found)
        }
    }

    private fun decodeSampledBitmap(uri: android.net.Uri): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        var sample = 1
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, o2)
        }
    }

    private fun handleImport(raw: String) {
        val uri = VlessParser.extractUri(raw)
        if (uri == null) {
            toast(if (VlessParser.isKnownScheme(raw)) R.string.import_unsupported else R.string.import_failed)
            return
        }
        val parsed = VlessParser.parse(uri)
        if (parsed == null || parsed.address.isBlank() || parsed.uuid.isBlank()) {
            toast(R.string.import_failed)
            return
        }
        if (ProfileStore.profiles.value.any { it.toShareUri() == parsed.toShareUri() }) {
            toast(R.string.import_exists)
            return
        }
        ProfileStore.upsert(parsed)
        toast(getString(R.string.imported_ok, parsed.displayName()))
        PingEngine.ping(parsed)
        ProfileStore.setActive(parsed.id)
    }

    // ------------------------------------------------------------ edit/share

    private fun openEditor(profile: ServerProfile?) {
        val i = Intent(this, EditActivity::class.java)
        i.putExtra(EditActivity.EXTRA_ID, profile?.id ?: -1L)
        startActivity(i)
    }

    private fun confirmDelete(profile: ServerProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ask_delete_title)
            .setMessage(getString(R.string.ask_delete_msg, profile.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ProfileStore.delete(profile.id)
                toast(R.string.deleted_ok)
                if (profile.id == ProfileStore.activeId.value &&
                    VpnBus.state.value == VpnState.CONNECTED
                ) sendStop()
            }
            .show()
    }

    private fun shareLink(uri: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, uri)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_link)))
    }

    private fun showQrDialog(title: String, content: String) {
        val bitmap: Bitmap = try {
            QrUtil.generate(content)
        } catch (t: Throwable) {
            toast(R.string.import_failed)
            return
        }
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = resources.displayMetrics.widthPixels
            setImageBitmap(bitmap)
        }
        val wrap = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(iv)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vless", text))
        toast(R.string.copied_ok)
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_ping_all -> {
                val list = ProfileStore.profiles.value
                PingEngine.pingAll(list)
                toast(getString(R.string.pinging, list.size))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Tab switch = replace the current screen with the target tab. */
    private fun openTab(cls: Class<*>) {
        startActivity(Intent(this, cls))
        finish()
        overridePendingTransition(0, 0)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /**
     * Silently refreshes auto-update subscriptions that are older than 12h
     * (or never fetched). Runs once per activity creation.
     */
    private fun autoUpdateSubscriptions() {
        val staleMs = 12 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val due = SubStore.subs.value.filter { it.autoUpdate && now - it.lastUpdated > staleMs }
        if (due.isEmpty()) return
        lifecycleScope.launch {
            for (sub in due) {
                try {
                    SubUpdater.update(sub.id)
                } catch (t: Throwable) {
                    // silent: background refresh must not nag the user
                }
            }
        }
    }
}
