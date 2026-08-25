package com.lionray.vpn.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lionray.vpn.R
import com.lionray.vpn.databinding.ActivityAppRulesBinding
import com.lionray.vpn.util.SettingsStore

/**
 * Lets the user pick apps whose traffic must BYPASS the VPN entirely
 * (VpnService.addDisallowedApplication).
 */
class AppRulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppRulesBinding
    private lateinit var adapter: AppsAdapter
    private var all: List<AppRow> = emptyList()

    data class AppRow(val label: String, val pkg: String, val icon: Drawable?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        SettingsStore.initBypassMirror(this)
        all = loadApps()
        adapter = AppsAdapter(all)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                adapter.submit(
                    if (q.isEmpty()) all
                    else all.filter {
                        it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
                    }
                )
            }
        })
    }

    /** System apps that users commonly want to bypass VPN for are
     *  always shown, even though they ship with the OS. */
    private val SYSTEM_WHITELIST = setOf(
        "com.android.chrome",
        "com.UCMobile",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.android.browser",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.photos",
        "com.google.android.apps.docs",
        "com.google.android.apps.magazines",
        "com.google.android.apps.tachyon",
        "com.viber.voip",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.whatsapp",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.twitter.android",
        "org.telegram.messenger",
        "com.discord",
        "com.spotify.music",
        "com.kakao.talk",
        "com.lineerner.linelite",
        "com.tencent.mm",
        "com.alibaba.android.rimet",
        "com.android.messaging",
        "com.android.dialer",
        "com.android.contacts",
        "com.android.gallery3d",
        "com.android.filemanager",
        "com.android.calculator2",
        "com.android.calendar",
        "com.android.deskclock"
    )

    /** true when the app was installed by the USER (sideload/Play) —
     *  preinstalled system apps are hidden UNLESS they're in our whitelist. */
    private fun isUserApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
            SYSTEM_WHITELIST.contains(ai.packageName)

    private fun loadApps(): List<AppRow> {
        val pm = packageManager
        val out = ArrayList<AppRow>()
        val seen = HashSet<String>()

        // every USER-installed package; Android 11+ needs QUERY_ALL_PACKAGES
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
            if (!isUserApp(ai)) continue
            out.add(
                AppRow(
                    label = ai.loadLabel(pm)?.toString() ?: ai.packageName,
                    pkg = ai.packageName,
                    icon = try { ai.loadIcon(pm) } catch (_: Throwable) { null }
                )
            )
        }

        // launcher entries too (nicer labels/icons when available)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(intent, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            val ai = info.activityInfo.applicationInfo ?: continue
            if (!isUserApp(ai)) continue
            out.add(
                AppRow(
                    label = info.loadLabel(pm)?.toString() ?: pkg,
                    pkg = pkg,
                    icon = info.loadIcon(pm)
                )
            )
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private class AppsAdapter(items: List<AppRow>) :
        RecyclerView.Adapter<AppsAdapter.VH>() {

        private var items: List<AppRow> = items
        private val selected = SettingsStore.bypassAppSet

        fun submit(list: List<AppRow>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivIcon)
            val label: TextView = v.findViewById(R.id.tvLabel)
            val pkg: TextView = v.findViewById(R.id.tvPkg)
            val chk: CheckBox = v.findViewById(R.id.chkBypass)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = items[position]
            h.label.text = app.label
            h.pkg.text = app.pkg
            h.icon.setImageDrawable(app.icon)
            h.chk.setOnCheckedChangeListener(null)
            h.chk.isChecked = selected.contains(app.pkg)
            h.chk.setOnCheckedChangeListener { _, _ ->
                if (SettingsStore.toggleBypassApp(h.itemView.context, app.pkg)) {
                    selected.add(app.pkg)
                } else {
                    selected.remove(app.pkg)
                }
            }
        }
    }
}
