package com.lionray.vpn.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lionray.vpn.R
import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.data.Subscription
import com.lionray.vpn.databinding.ItemHeaderBinding
import com.lionray.vpn.databinding.ItemServerBinding
import com.lionray.vpn.util.PingEngine

/** One row of the server list: either a group header or a server card. */
sealed class Row {
    class Header(val title: String, val count: Int) : Row()
    class Server(val p: ServerProfile) : Row()
}

class ServerAdapter(
    private val onClick: (ServerProfile) -> Unit,
    private val onLongClick: (ServerProfile, View) -> Unit,
    private val onEdit: (ServerProfile) -> Unit,
    private val onDelete: (ServerProfile) -> Unit,
    private val onPing: (ServerProfile) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1
        private const val PAYLOAD_PING = "ping"
    }

    init {
        setHasStableIds(true)
    }

    private var rows: List<Row> = emptyList()
    private val pings = HashMap<Long, Int>()
    private val subNames = HashMap<Long, String>()
    var activeId: Long = 0L

    fun submit(list: List<ServerProfile>, subs: List<Subscription>) {
        subNames.clear()
        subs.forEach { subNames[it.id] = it.displayName() }
        rows = buildRows(list, subs)
        notifyDataSetChanged()
    }

    private fun buildRows(
        list: List<ServerProfile>,
        subs: List<Subscription>
    ): List<Row> {
        val out = ArrayList<Row>()
        val manual = list.filter { it.subId == 0L }
        if (manual.isNotEmpty()) out.add(Row.Header("Servers", manual.size))
        manual.forEach { out.add(Row.Server(it)) }
        for (sub in subs.sortedBy { it.id }) {
            val servers = list.filter { it.subId == sub.id }
            if (servers.isEmpty()) continue
            out.add(Row.Header(sub.displayName(), servers.size))
            servers.forEach { out.add(Row.Server(it)) }
        }
        return out
    }

    fun updatePings(map: Map<Long, Int>) {
        // only rebind the rows whose latency actually changed, so the list
        // never jumps around while a ping sweep is running
        val changed = ArrayList<Int>()
        for (i in rows.indices) {
            val r = rows[i]
            if (r is Row.Server && pings[r.p.id] != map[r.p.id]) changed.add(i)
        }
        pings.clear()
        pings.putAll(map)
        for (pos in changed) notifyItemChanged(pos, PAYLOAD_PING)
    }

    fun setActive(id: Long) {
        activeId = id
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_SERVER

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is Row.Header -> -("h:${r.title}").hashCode().toLong()
        is Row.Server -> r.p.id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            VH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(r)
            is Row.Server -> (holder as VH).bind(r.p)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty() || holder !is VH) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val r = rows[position]
        if (r is Row.Server) holder.bindPing(r.p)
    }

    class HeaderVH(private val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.Header) {
            b.tvHeader.text = h.title
            b.tvCount.text = h.count.toString()
        }
    }

    inner class VH(private val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {

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

            b.root.setOnClickListener { onClick(p) }
            b.root.setOnLongClickListener { v -> onLongClick(p, v); true }
            b.btnEdit.setOnClickListener { onEdit(p) }
            b.btnDelete.setOnClickListener { onDelete(p) }
            b.btnPing.setOnClickListener { onPing(p) }
        }

        /** Lightweight rebind used when only the latency pill changed. */
        fun bindPing(p: ServerProfile) = renderPing(b.root.context, p)

        private fun renderPing(ctx: android.content.Context, p: ServerProfile) {            val ms = pings[p.id]
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
            b.tvPing.backgroundTintList = ColorStateList.valueOf(c)
        }

        private fun dp(v: Int): Int =
            (v * b.root.context.resources.displayMetrics.density).toInt()
    }
}
