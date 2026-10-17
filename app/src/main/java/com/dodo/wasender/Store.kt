package com.dodo.wasender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Tiny persistent store shared by the panel (through the JS bridge) and the send engine.
 * Replaces chrome.storage.local. Lives at process level so the engine keeps working
 * even if the activity is recreated.
 */
object Store {
    private val lock = Any()
    private var store = JSONObject()
    private var storeFile: File? = null
    private var attFile: File? = null

    fun init(ctx: Context) {
        synchronized(lock) {
            if (storeFile != null) return
            val dir = ctx.applicationContext.filesDir
            val sf = File(dir, "store.json")
            storeFile = sf
            attFile = File(dir, "attachment.json")
            store = try { JSONObject(sf.readText()) } catch (e: Exception) { JSONObject() }
            // never auto-resume a send after the app process was restarted
            val st = store.optJSONObject("state")
            if (st != null && st.optBoolean("running", false)) {
                st.put("running", false)
                st.put("status", "تم الإيقاف")
                persist()
            }
        }
    }

    private fun persist() {
        try { storeFile?.writeText(store.toString()) } catch (e: Exception) { }
    }

    private fun keysOf(json: String): List<String> {
        return when (val v = JSONTokener(json).nextValue()) {
            is String -> listOf(v)
            is JSONArray -> (0 until v.length()).map { v.getString(it) }
            else -> store.keys().asSequence().toList()
        }
    }

    // ---------- used by the JS bridge ----------
    fun get(keysJson: String): String = synchronized(lock) {
        val out = JSONObject()
        for (k in keysOf(keysJson)) {
            if (k == "attachment") {
                val f = attFile
                if (f != null && f.exists()) {
                    try { out.put(k, JSONObject(f.readText())) } catch (e: Exception) { }
                }
            } else if (store.has(k)) {
                out.put(k, store.get(k))
            }
        }
        out.toString()
    }

    fun set(objJson: String) {
        synchronized(lock) {
            val o = JSONObject(objJson)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k == "attachment") {
                    try { attFile?.writeText(o.get(k).toString()) } catch (e: Exception) { }
                } else {
                    store.put(k, o.get(k))
                }
            }
            persist()
        }
    }

    fun remove(keysJson: String) {
        synchronized(lock) {
            for (k in keysOf(keysJson)) {
                if (k == "attachment") attFile?.delete() else store.remove(k)
            }
            persist()
        }
    }

    // ---------- used by the send engine ----------
    fun <T> readState(block: (JSONObject) -> T): T? = synchronized(lock) {
        val st = store.optJSONObject("state")
        if (st == null) null else block(st)
    }

    fun updateState(block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val st = store.optJSONObject("state") ?: return
            block(st)
            persist()
        }
    }

    fun isRunning(): Boolean = readState { it.optBoolean("running", false) } ?: false

    fun attachment(): JSONObject? = synchronized(lock) {
        val f = attFile
        if (f != null && f.exists()) {
            try { JSONObject(f.readText()) } catch (e: Exception) { null }
        } else {
            null
        }
    }
}
