package com.lionray.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple JSON-file backed profile storage with StateFlow observability.
 */
object ProfileStore {

    private const val PREF_NAME = "lionray_prefs"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val FILE_NAME = "profiles.json"

    private var appContext: Context? = null
    private lateinit var file: File
    private val lock = Any()

    val profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val activeId = MutableStateFlow(0L)

    private val prefs by lazy {
        appContext!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(appContext!!.filesDir, FILE_NAME)
        loadFromDisk()
    }

    fun get(id: Long): ServerProfile? = profiles.value.firstOrNull { it.id == id }

    fun activeProfile(): ServerProfile? = get(activeId.value)

    fun upsert(profile: ServerProfile) {
        synchronized(lock) {
            val current = profiles.value.toMutableList()
            if (profile.id == 0L || current.none { it.id == profile.id }) {
                val p = if (profile.id == 0L) profile.copy(id = newId()) else profile
                current.add(0, p)
                publish(current)
                persist()
                if (activeId.value == 0L) setActive(p.id)
            } else {
                val idx = current.indexOfFirst { it.id == profile.id }
                current[idx] = profile
                publish(current)
                persist()
            }
        }
    }

    fun delete(id: Long) {
        synchronized(lock) {
            val current = profiles.value.filter { it.id != id }
            publish(current)
            persist()
            if (activeId.value == id) {
                setActive(current.firstOrNull()?.id ?: 0L)
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            publish(emptyList())
            persist()
            setActive(0L)
        }
    }

    /**
     * Atomically replaces all servers belonging to [subId] with [incoming],
     * preserving ids of unchanged entries so the active selection survives
     * a refresh. Keeps the active profile if it still exists.
     */
    fun replaceSub(subId: Long, incoming: List<ServerProfile>) {
        synchronized(lock) {
            val old = profiles.value
            val oldSub = old.filter { it.subId == subId }
            val oldByUri = oldSub.associate { it.toShareUri() to it }
            val merged = incoming.map {
                val prev = oldByUri[it.toShareUri()]
                // brand-new keys MUST get a real unique id; leaving 0 makes
                // every row share one stable-id and crashes selection.
                // Carry over the resolved country flag across refreshes.
                if (prev != null) {
                    it.copy(
                        id = prev.id,
                        countryCode = prev.countryCode.ifBlank { it.countryCode }
                    )
                } else {
                    it.copy(id = newId())
                }
            }
            // keep manual + other-sub servers first, then this sub's fresh list
            val others = old.filter { it.subId != subId }
            publish(others + merged)
            persist()
            if (activeId.value != 0L && noneActive()) {
                setActive(merged.firstOrNull()?.id ?: others.firstOrNull()?.id ?: 0L)
            } else if (activeId.value == 0L && merged.isNotEmpty() && others.isEmpty()) {
                setActive(merged.first().id)
            }
        }
    }

    private fun noneActive(): Boolean =
        profiles.value.none { it.id == activeId.value }

    /** Removes every server of [subId]; fixes the active selection afterwards. */
    fun deleteBySub(subId: Long) {
        synchronized(lock) {
            publish(profiles.value.filter { it.subId != subId })
            persist()
            if (noneActive()) setActive(profiles.value.firstOrNull()?.id ?: 0L)
        }
    }

    fun setActive(id: Long) {
        activeId.value = id
        prefs.edit().putLong(KEY_ACTIVE_ID, id).apply()
    }

    private fun publish(list: List<ServerProfile>) {
        profiles.value = list
    }

    // Monotonic id source: never returns the same value twice, even when
    // importing a whole subscription within one millisecond.
    private val idCounter = AtomicLong(System.currentTimeMillis())

    private fun newId(): Long = idCounter.incrementAndGet()

    private fun loadFromDisk() {
        try {
            if (!file.exists()) {
                publish(emptyList())
                activeId.value = prefs.getLong(KEY_ACTIVE_ID, 0L)
                return
            }
            val arr = JSONArray(file.readText())
            // sanitize legacy data: zero or duplicated ids crash selection
            var changed = false
            val seen = HashSet<Long>()
            val list = ArrayList<ServerProfile>(arr.length())
            for (i in 0 until arr.length()) {
                var p = ServerProfile.fromJson(arr.getJSONObject(i))
                if (p.id <= 0L || !seen.add(p.id)) {
                    p = p.copy(id = newId())
                    changed = true
                }
                list.add(p)
            }
            publish(list)
            activeId.value = prefs.getLong(KEY_ACTIVE_ID, 0L)
            if (changed) persist()
        } catch (t: Throwable) {
            publish(emptyList())
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (p in profiles.value) arr.put(p.toJson())
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(arr.toString(2))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }
}
