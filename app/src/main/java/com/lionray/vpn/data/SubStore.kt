package com.lionray.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File

/**
 * JSON-file backed subscription storage with StateFlow observability.
 */
object SubStore {

    private const val FILE_NAME = "subscriptions.json"

    private var appContext: Context? = null
    private lateinit var file: File
    private val lock = Any()

    val subs = MutableStateFlow<List<Subscription>>(emptyList())

    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(appContext!!.filesDir, FILE_NAME)
        loadFromDisk()
        val maxId = subs.value.maxOfOrNull { it.id } ?: 0
        if (maxId >= idCounter.get()) idCounter.set(maxId + 1)
    }

    fun get(id: Long): Subscription? = subs.value.firstOrNull { it.id == id }

    fun upsert(sub: Subscription) {
        synchronized(lock) {
            val current = subs.value.toMutableList()
            if (sub.id == 0L || current.none { it.id == sub.id }) {
                val s = if (sub.id == 0L) sub.copy(id = newId()) else sub
                current.add(s)
            } else {
                val idx = current.indexOfFirst { it.id == sub.id }
                current[idx] = sub
            }
            publish(current)
            persist()
        }
    }

    fun delete(id: Long) {
        synchronized(lock) {
            publish(subs.value.filter { it.id != id })
            persist()
        }
    }

    fun markUpdated(id: Long, timeMs: Long) {
        get(id)?.let { upsert(it.copy(lastUpdated = timeMs)) }
    }

    private fun publish(list: List<Subscription>) {
        subs.value = list
    }

    private fun newId(): Long = idCounter.getAndIncrement()

    private fun loadFromDisk() {
        try {
            if (!file.exists()) {
                publish(emptyList())
                return
            }
            val arr = JSONArray(file.readText())
            val list = ArrayList<Subscription>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(Subscription.fromJson(arr.getJSONObject(i)))
            }
            publish(list)
        } catch (t: Throwable) {
            publish(emptyList())
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (s in subs.value) arr.put(s.toJson())
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(arr.toString(2))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }
}
