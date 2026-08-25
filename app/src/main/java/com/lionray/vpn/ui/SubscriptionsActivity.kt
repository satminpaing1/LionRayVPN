package com.lionray.vpn.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lionray.vpn.R
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.data.SubStore
import com.lionray.vpn.data.Subscription
import com.lionray.vpn.databinding.ActivitySubscriptionsBinding
import com.lionray.vpn.databinding.DialogSubEditBinding
import com.lionray.vpn.databinding.ItemHeaderBinding
import com.lionray.vpn.databinding.ItemServerBinding
import com.lionray.vpn.databinding.ItemSubCompactBinding
import com.lionray.vpn.util.PingEngine
import com.lionray.vpn.util.SubUpdater
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SubscriptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionsBinding

    /** Filled while the add/edit dialog is open so a scanned URL lands in it. */
    private var activeDialogUrl: com.google.android.material.textfield.TextInputEditText? = null

    private val subQrLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents ?: return@registerForActivityResult
            if (contents.startsWith("http://") || contents.startsWith("https://")) {
                activeDialogUrl?.setText(contents)
            } else {
                toast(R.string.subs_invalid_url)
            }
        }

    private val qrLauncher =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { smartImportOrKey(it) }
        }

    private lateinit var adapter: ConfigsAdapter

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        adapter = ConfigsAdapter(
            onServerClick = { p ->
                ProfileStore.setActive(p.id)
                PingEngine.ping(p)
                toast(getString(R.string.imported_ok, p.displayName()))
            },
            onLongClick = { p, v -> onServerLongClicked(p, v) },
            onEdit = { openEditor(it) },
            onDelete = { confirmDeleteServer(it) },
            onPing = { PingEngine.ping(it) },
            onShare = { showShareDialog(it) },
            onSubClick = { showEditDialog(it) },
            onSubUpdate = { updateNow(it) },
            onSubAutoSelect = { autoSelect(it) },
            onSubDelete = { confirmDeleteSub(it) },
            onSubToggle = { sub, checked ->
                SubStore.upsert(sub.copy(autoUpdate = checked))
            }
        )
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { showAddSheet() }
        binding.btnPingAll.setOnClickListener {
            val list = ProfileStore.profiles.value
            if (list.isEmpty()) {
                toast(R.string.err_no_server)
            } else {
                PingEngine.pingAll(list)
                toast(getString(R.string.pinging, list.size))
            }
        }

        // bottom tabs: preselect BEFORE attaching the listener so no
        // spurious callback fires on startup
        binding.bottomNav.selectedItemId = R.id.nav_configs
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { openTab(MainActivity::class.java); true }
                R.id.nav_settings -> { openTab(SettingsActivity::class.java); true }
                else -> true
            }
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ProfileStore.profiles.collect { list ->
                runCatching {
                    adapter.submitProfiles(list)
                    refreshEmpty()
                }
            }
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            SubStore.subs.collect { subs ->
                runCatching {
                    adapter.submitSubs(subs)
                    refreshEmpty()
                }
            }
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            PingEngine.results.collect { map ->
                runCatching { adapter.updatePings(map) }
            }
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ProfileStore.activeId.collect { id ->
                runCatching { adapter.setActive(id) }
            }
        }
    }

    /** Back key returns to Home like the bottom tab does. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        openTab(MainActivity::class.java)
    }

    private fun refreshEmpty() {
        val nothing = ProfileStore.profiles.value.isEmpty() && SubStore.subs.value.isEmpty()
        binding.tvEmpty.visibility = if (nothing) View.VISIBLE else View.GONE
    }

    private fun openEditor(profile: ServerProfile?) {
        val i = Intent(this, EditActivity::class.java)
        i.putExtra(EditActivity.EXTRA_ID, profile?.id ?: -1L)
        startActivity(i)
    }

    /** (+) sheet: clipboard / QR camera / QR photo / manual / subscription. */
    private fun showAddSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
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
        sheet.findViewById<View>(R.id.actSubscription)?.setOnClickListener {
            sheet.dismiss(); showEditDialog(null)
        }
        sheet.show()
    }

    private val galleryLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) decodeQrFromGallery(uri)
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

    private fun decodeQrFromGallery(uri: android.net.Uri) {
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: run { toast(R.string.qr_not_found); return }
            var sample = 1
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
            val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, o2)
            } ?: run { toast(R.string.qr_not_found); return }
            val text = com.lionray.vpn.util.QrUtil.readFromBitmap(bmp)
            bmp.recycle()
            if (text.isNullOrBlank()) toast(R.string.qr_not_found) else handleImport(text)
        } catch (t: Throwable) {
            toast(R.string.qr_not_found)
        }
    }

    private fun importFromClipboard() {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
            android.content.ClipboardManager
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
            if (!com.lionray.vpn.util.SubUpdater.smartImport(this@SubscriptionsActivity, raw)) {
                handleImport(raw)
            }
        }
    }

    private fun handleImport(raw: String) {
        val uri = com.lionray.vpn.data.VlessParser.extractUri(raw)
        if (uri == null) {
            toast(
                if (com.lionray.vpn.data.VlessParser.isKnownScheme(raw)) R.string.import_unsupported
                else R.string.import_failed
            )
            return
        }
        val parsed = com.lionray.vpn.data.VlessParser.parse(uri)
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

    private fun confirmDeleteServer(profile: ServerProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ask_delete_title)
            .setMessage(getString(R.string.ask_delete_msg, profile.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ProfileStore.delete(profile.id)
                toast(R.string.deleted_ok)
            }
            .show()
    }

    private fun confirmDeleteSub(sub: Subscription) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subs_delete_title)
            .setMessage(getString(R.string.subs_delete_msg, sub.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ProfileStore.deleteBySub(sub.id)
                SubStore.delete(sub.id)
                toast(R.string.deleted_ok)
            }
            .show()
    }

    private fun updateNow(sub: Subscription) {
        toast(getString(R.string.subs_updating, sub.displayName()))
        lifecycleScope.launch {
            try {
                val n = SubUpdater.update(sub.id)
                toast(getString(R.string.subs_updated_ok, sub.displayName(), n))
            } catch (t: Throwable) {
                toast(getString(R.string.subs_update_fail, t.message ?: t.toString()))
            }
        }
    }

    /**
     * Auto Select (⚡): pings every key of this subscription, waits for the
     * results and activates the fastest reachable one.
     */
    private fun autoSelect(sub: Subscription) {
        val keys = ProfileStore.profiles.value.filter { it.subId == sub.id }
        if (keys.isEmpty()) {
            toast(R.string.err_no_server)
            return
        }
        toast(getString(R.string.auto_selecting))
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            keys.forEach { PingEngine.ping(it) }
            // wait until every key has a result (or give up after 25 s)
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline) {
                val res = PingEngine.results.value
                val pending = keys.count { p ->
                    val ms = res[p.id]
                    ms == null || ms == 0
                }
                if (pending == 0) break
                kotlinx.coroutines.delay(300)
            }
            val res = PingEngine.results.value
            val best = keys
                .mapNotNull { p ->
                    val ms = res[p.id]
                    if (ms != null && ms > 0) p to ms else null
                }
                .minByOrNull { it.second }?.first
            if (best == null) {
                toast(R.string.auto_select_none)
            } else {
                ProfileStore.setActive(best.id)
                PingEngine.ping(best)
                toast(getString(R.string.auto_selected_ok, best.displayName()))
            }
        }
    }

    private fun onServerLongClicked(profile: ServerProfile, anchor: View) {
        showShareDialog(profile)
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

    private fun shareLink(uri: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, uri)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_link)))
    }

    private fun showQrDialog(title: String, content: String) {
        val bitmap = try {
            com.lionray.vpn.util.QrUtil.generate(content)
        } catch (t: Throwable) {
            toast(R.string.import_failed); return
        }
        val iv = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = resources.displayMetrics.widthPixels
            setImageBitmap(bitmap)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(iv)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
            android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("vless", text)
        )
        toast(R.string.copied_ok)
    }

    private fun openTab(cls: Class<*>) {
        // a tab switch REPLACES the current screen: the task never holds
        // more than one tab, so nothing can "fall back" behind the user
        startActivity(Intent(this, cls))
        finish()
        overridePendingTransition(0, 0)
    }

    private fun showEditDialog(existing: Subscription?) {
        val dlgBinding = DialogSubEditBinding.inflate(layoutInflater)
        existing?.let {
            dlgBinding.etName.setText(it.remark)
            dlgBinding.etUrl.setText(it.url)
        }
        dlgBinding.btnScanSubQr.setOnClickListener {
            activeDialogUrl = dlgBinding.etUrl
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(CapturePortrait::class.java)
            subQrLauncher.launch(options)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    if (existing == null) R.string.subs_add_title else R.string.subs_edit_title
                )
            )
            .setView(dlgBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dlgBinding.etName.text?.toString()?.trim().orEmpty()
                val url = dlgBinding.etUrl.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast(R.string.subs_invalid_url)
                    return@setPositiveButton
                }
                val base = existing ?: Subscription(id = 0L, autoUpdate = true)
                val sub = base.copy(remark = name, url = url)
                SubStore.upsert(sub)
                if (existing == null) updateNow(SubStore.get(sub.id) ?: sub)
            }
            .show()
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
}

/** One row of the configs page: subscription manager card, header or server. */
sealed class CRow {
    class Header(val title: String, val count: Int) : CRow()
    class Server(val p: ServerProfile) : CRow()
    class Sub(val s: Subscription) : CRow()
}

class ConfigsAdapter(
    private val onServerClick: (ServerProfile) -> Unit,
    private val onLongClick: (ServerProfile, View) -> Unit,
    private val onEdit: (ServerProfile) -> Unit,
    private val onDelete: (ServerProfile) -> Unit,
    private val onPing: (ServerProfile) -> Unit,
    private val onShare: (ServerProfile) -> Unit,
    private val onSubClick: (Subscription) -> Unit,
    private val onSubUpdate: (Subscription) -> Unit,
    private val onSubAutoSelect: (Subscription) -> Unit,
    private val onSubDelete: (Subscription) -> Unit,
    private val onSubToggle: (Subscription, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1
        private const val TYPE_SUB = 2
        private const val PAYLOAD_PING = "ping"
        /** section header markers resolved to localized strings at bind time */
        const val MARK_SUBS = "\u0001SUBS"
        const val MARK_SERVERS = "\u0001SRV"
    }

    init {
        setHasStableIds(true)
    }

    private var profiles: List<ServerProfile> = emptyList()
    private var subs: List<Subscription> = emptyList()
    private var rows: List<CRow> = emptyList()
    private val pings = HashMap<Long, Int>()
    var activeId: Long = 0L

    fun submitProfiles(list: List<ServerProfile>) {
        profiles = list
        rebuild()
    }

    fun submitSubs(list: List<Subscription>) {
        subs = list
        rebuild()
    }

    private fun rebuild() {
        val out = ArrayList<CRow>()
        // manually added servers always live at the very top
        val manual = profiles.filter { it.subId == 0L }.sortedWith(pingOrder)
        if (manual.isNotEmpty()) {
            out.add(CRow.Header(MARK_SERVERS, manual.size))
            manual.forEach { out.add(CRow.Server(it)) }
        }
        // each subscription card, followed directly by its own keys
        for (sub in subs.sortedBy { it.id }) {
            out.add(CRow.Sub(sub))
            profiles.filter { it.subId == sub.id }
                .sortedWith(pingOrder)
                .forEach { out.add(CRow.Server(it)) }
        }
        rows = out
        notifyDataSetChanged()
    }

    /** Lowest ping first; untested in the middle; timeouts at the bottom. */
    private val pingOrder =
        compareBy<ServerProfile>(
            { rank(it) },
            { p -> pings[p.id] ?: Int.MAX_VALUE },
            { it.displayName().lowercase() }
        )

    private fun rank(p: ServerProfile): Int {
        val ms = pings[p.id]
        return when {
            ms == null || ms == 0 -> 1   // not tested yet
            ms < 0 || ms == PingEngine.TIMEOUT_MS -> 2  // dead -> bottom
            else -> 0                    // working, fastest first
        }
    }

    fun updatePings(map: Map<Long, Int>) {
        val changed = ArrayList<Int>()
        for (i in rows.indices) {
            val r = rows[i]
            if (r is CRow.Server && pings[r.p.id] != map[r.p.id]) changed.add(i)
        }
        pings.clear()
        pings.putAll(map)
        for (pos in changed) notifyItemChanged(pos, PAYLOAD_PING)
        // re-sort (fastest to top, dead to bottom) once results settle
        if (changed.isNotEmpty()) {
            handler.removeCallbacks(resortRun)
            handler.postDelayed(resortRun, 800)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val resortRun = Runnable { rebuild() }

    fun setActive(id: Long) {
        if (activeId == id) return
        activeId = id
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is CRow.Header -> TYPE_HEADER
        is CRow.Server -> TYPE_SERVER
        is CRow.Sub -> TYPE_SUB
    }

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is CRow.Header -> -("h:${r.title}").hashCode().toLong()
        is CRow.Server -> r.p.id
        is CRow.Sub -> -(1_000_000L + r.s.id)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemHeaderBinding.inflate(inf, parent, false))
            TYPE_SUB -> SubVH(ItemSubCompactBinding.inflate(inf, parent, false))
            else -> SVH(ItemServerBinding.inflate(inf, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is CRow.Header -> (holder as HeaderVH).bind(r)
            is CRow.Server -> (holder as SVH).bind(r.p)
            is CRow.Sub -> (holder as SubVH).bind(r.s)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty() || holder !is SVH) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val r = rows[position]
        if (r is CRow.Server) holder.bindPing(r.p)
    }

    class HeaderVH(private val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: CRow.Header) {
            val ctx = b.root.context
            b.tvHeader.text = when (h.title) {
                ConfigsAdapter.MARK_SUBS -> ctx.getString(R.string.section_subscriptions)
                ConfigsAdapter.MARK_SERVERS -> ctx.getString(R.string.section_servers)
                else -> h.title
            }
            b.tvCount.text = h.count.toString()
        }
    }

    inner class SubVH(private val b: ItemSubCompactBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(s: Subscription) {
            val ctx = b.root.context
            b.tvName.text = s.displayName()
            val count = profiles.count { it.subId == s.id }
            val updated = if (s.lastUpdated > 0) {
                ctx.getString(
                    R.string.subs_last_updated,
                    ago(ctx, System.currentTimeMillis() - s.lastUpdated)
                )
            } else {
                ctx.getString(R.string.subs_never_updated)
            }
            b.tvInfo.text = "$count • $updated"

            b.swAuto.setOnCheckedChangeListener(null)
            b.swAuto.isChecked = s.autoUpdate
            b.swAuto.setOnCheckedChangeListener { _: CompoundButton, checked ->
                onSubToggle(s, checked)
            }

            b.btnUpdate.setOnClickListener { onSubUpdate(s) }
            b.btnAuto.setOnClickListener { onSubAutoSelect(s) }
            b.btnDelete.setOnClickListener { onSubDelete(s) }
            b.root.setOnClickListener { onSubClick(s) }
        }

        private fun ago(ctx: android.content.Context, ms: Long): String {
            val min = TimeUnit.MILLISECONDS.toMinutes(ms)
            return when {
                min < 1 -> ctx.getString(R.string.ago_now)
                min < 60 -> ctx.getString(R.string.ago_min, min)
                TimeUnit.MILLISECONDS.toHours(ms) < 24 ->
                    ctx.getString(R.string.ago_hours, TimeUnit.MILLISECONDS.toHours(ms))
                else -> ctx.getString(R.string.ago_days, TimeUnit.MILLISECONDS.toDays(ms))
            }
        }
    }

    inner class SVH(private val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(p: ServerProfile) {
            val ctx = b.root.context

            b.tvName.text = p.displayName()
            b.tvAddr.text = p.maskedAddress()

            val meta = buildString {
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
            b.tvMeta.text = meta

            val active = p.id == activeId
            b.card.strokeWidth = if (active) dp(2) else dp(1)
            b.card.strokeColor = ContextCompat.getColor(
                ctx, if (active) R.color.brand else R.color.surface_card_stroke
            )
            b.card.setCardBackgroundColor(
                ContextCompat.getColor(
                    ctx, if (active) R.color.surface_card_active else R.color.surface_card
                )
            )

            renderPing(ctx, p)

            b.root.setOnClickListener { onServerClick(p) }
            b.root.setOnLongClickListener { v -> onLongClick(p, v); true }
            b.btnEdit.setOnClickListener { onEdit(p) }
            b.btnDelete.setOnClickListener { onDelete(p) }
            b.btnPing.setOnClickListener { onPing(p) }
            b.btnShare.setOnClickListener { onShare(p) }
        }

        fun bindPing(p: ServerProfile) = renderPing(b.root.context, p)

        private fun renderPing(ctx: android.content.Context, p: ServerProfile) {
            val ms = pings[p.id]
            val color: Int
            when {
                ms == null || ms == 0 -> {
                    b.tvPing.text = ctx.getString(R.string.latency_none)
                    color = R.color.ping_gray
                }
                ms == PingEngine.TIMEOUT_MS || ms < 0 -> {
                    b.tvPing.text = ctx.getString(R.string.latency_timeout)
                    color = R.color.ping_red
                }
                ms < 200 -> {
                    b.tvPing.text = ctx.getString(R.string.latency_ms, ms)
                    color = R.color.ping_green
                }
                ms < 500 -> {
                    b.tvPing.text = ctx.getString(R.string.latency_ms, ms)
                    color = R.color.ping_amber
                }
                else -> {
                    b.tvPing.text = ctx.getString(R.string.latency_ms, ms)
                    color = R.color.ping_red
                }
            }
            val c = ContextCompat.getColor(ctx, color)
            b.tvPing.backgroundTintList =
                android.content.res.ColorStateList.valueOf(c)
        }

        private fun dp(v: Int): Int =
            (v * b.root.context.resources.displayMetrics.density).toInt()
    }
}
