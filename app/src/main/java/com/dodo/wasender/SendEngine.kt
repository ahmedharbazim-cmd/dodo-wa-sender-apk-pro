package com.dodo.wasender

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Sends the queue through the native WhatsApp app:
 *  1. opens the chat (text prefilled) with an Intent,
 *  2. waits for the Send button through the accessibility service,
 *  3. taps it and checks that it disappeared,
 *  4. returns to this app and waits the configured delay.
 * State (queue/index/log/...) is kept in Store, so the panel UI is unchanged.
 */
object SendEngine {

    @Volatile private var generation = 0
    private var worker: Thread? = null
    private var prepared: File? = null

    private class Job(val phone: String, val text: String, val hasMedia: Boolean)
    private class Res(val ok: Boolean, val note: String = "", val stopped: Boolean = false)
    private class Cfg(val sent: Int, val minD: Double, val maxD: Double, val batch: Int, val pause: Double)

    private val sendWords = setOf("send", "إرسال", "ارسال", "envoyer", "enviar", "senden", "invia", "gönder", "kirim")
    private val okLabels = setOf("ok", "موافق", "حسنا", "حسناً", "إغلاق", "close", "dismiss")
    private val invalidHints = listOf(
        "invalid", "not on whatsapp", "isn't on whatsapp", "not registered",
        "غير صالح", "غير صحيح", "غير مسجل", "ليس على واتساب", "غير موجود"
    )

    // ---------------- lifecycle ----------------

    @Synchronized
    fun start(): Boolean {
        val svc = WaService.instance ?: return false
        val gen = ++generation
        val old = worker
        val t = Thread {
            try { old?.join(20000) } catch (e: InterruptedException) { }
            try {
                loop(svc, gen)
            } catch (e: Throwable) {
                if (gen == generation) stopWith("خطأ: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                cleanupShared(svc) // don't leave the attached file lying in the cache
            }
        }
        t.isDaemon = true
        worker = t
        t.start()
        return true
    }

    /** Called when the accessibility permission is switched off. */
    fun serviceLost() {
        generation++
        stopWith("إذن Accessibility اتقفل — فعّله وكمّل")
    }

    private fun alive(gen: Int) = gen == generation && Store.isRunning()

    private fun cleanupShared(ctx: Context) {
        try { File(ctx.cacheDir, "share").listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
        prepared = null
    }

    private fun stopWith(msg: String) {
        Store.updateState { st ->
            st.put("running", false)
            st.put("status", msg)
        }
    }

    private fun finish() {
        Store.updateState { st ->
            st.put("running", false)
            st.put("status", "تم الانتهاء ✅")
            st.put("nextAt", 0)
        }
    }

    // ---------------- main loop ----------------

    private fun loop(svc: WaService, gen: Int) {
        val pkg = pickPkg(svc)
        if (pkg == null) { stopWith("واتساب مش متثبت على الموبايل"); return }

        prepared = null
        val hasMedia = Store.readState { it.optBoolean("hasMedia", false) } ?: false
        if (hasMedia) {
            prepared = try { prepareFile(svc) } catch (e: Exception) { null }
            if (prepared == null) { stopWith("الملف المرفق مش موجود أو بايظ"); return }
        }

        while (alive(gen)) {
            val job = nextJob()
            if (job == null) {
                svc.returnToApp(pkg)
                finish()
                return
            }
            Store.updateState { it.put("status", "جاري الإرسال إلى ${job.phone}...") }

            val res = sendOne(svc, pkg, job, gen)
            if (res.stopped) return
            record(job, res)
            svc.returnToApp(pkg)

            val finished = Store.readState {
                it.optInt("index", 0) >= (it.optJSONArray("queue")?.length() ?: 0)
            } ?: true
            if (finished) { finish(); return }
            if (!alive(gen)) return

            val cfg = readCfg() ?: return
            var delay = if (res.ok) cfg.minD + Random.nextDouble() * (cfg.maxD - cfg.minD) else 3.0
            if (res.ok && cfg.batch > 0 && cfg.sent % cfg.batch == 0) delay += cfg.pause * 60
            val nextAt = System.currentTimeMillis() + (delay * 1000).toLong()
            Store.updateState { st ->
                st.put("nextAt", nextAt)
                st.put("status", "انتظار...")
            }
            while (System.currentTimeMillis() < nextAt) {
                Thread.sleep(1000)
                if (!alive(gen)) return
            }
        }
    }

    private fun nextJob(): Job? = Store.readState { st ->
        val q = st.optJSONArray("queue")
        val i = st.optInt("index", 0)
        if (q == null || i >= q.length()) {
            null
        } else {
            val it = q.getJSONObject(i)
            Job(it.optString("phone"), it.optString("text"), st.optBoolean("hasMedia", false))
        }
    }

    private fun readCfg(): Cfg? = Store.readState { st ->
        Cfg(
            st.optInt("sent", 0),
            st.optDouble("minDelay", 15.0),
            st.optDouble("maxDelay", 30.0),
            st.optInt("batchSize", 0),
            st.optDouble("batchPause", 0.0)
        )
    }

    private fun record(job: Job, res: Res) {
        Store.updateState { st ->
            var log = st.optJSONArray("log")
            if (log == null) { log = JSONArray(); st.put("log", log) }
            log.put(JSONObject().put("phone", job.phone).put("ok", res.ok).put("note", res.note))
            if (res.ok) st.put("sent", st.optInt("sent", 0) + 1)
            else st.put("failed", st.optInt("failed", 0) + 1)
            st.put("index", st.optInt("index", 0) + 1)
        }
    }

    // ---------------- one message ----------------

    private fun sendOne(svc: WaService, pkg: String, job: Job, gen: Int): Res {
        val intent = try { buildIntent(svc, pkg, job) } catch (e: Exception) {
            return Res(false, "تجهيز الرسالة فشل: " + (e.message ?: ""))
        }
        try { svc.startActivity(intent) } catch (e: Exception) {
            return Res(false, "ما قدرتش أفتح واتساب: " + (e.message ?: ""))
        }

        // 1) wait until the Send button shows up (chat / media preview ready)
        val timeout = if (job.hasMedia) 35000L else 25000L
        val t0 = System.currentTimeMillis()
        var found = false
        while (System.currentTimeMillis() - t0 < timeout) {
            if (!alive(gen)) return Res(false, stopped = true)
            val root = svc.rootInActiveWindow
            if (root != null && root.packageName?.toString() == pkg) {
                if (findSend(root, pkg) != null) { found = true; break }
                val okBtn = invalidDialog(root)
                if (okBtn != null) {
                    click(svc, okBtn)
                    Thread.sleep(600)
                    return Res(false, "الرقم غير موجود على واتساب")
                }
            }
            Thread.sleep(400)
        }
        if (!found) return Res(false, "انتهت المهلة (ما ظهرش زر الإرسال)")

        Thread.sleep(800 + Random.nextLong(800))

        // 2) tap Send and make sure the button is gone (= message left the composer)
        for (attempt in 1..3) {
            if (attempt == 1 && !alive(gen)) return Res(false, stopped = true)
            val root = svc.rootInActiveWindow
            val btn = if (root != null && root.packageName?.toString() == pkg) findSend(root, pkg) else null
            if (btn == null && attempt > 1) return Res(true, "غير مؤكد")
            if (btn != null) click(svc, btn)

            Thread.sleep(1200)
            val t1 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t1 < 6000) {
                val r = svc.rootInActiveWindow
                if (r != null && r.packageName?.toString() == pkg && findSend(r, pkg) == null) {
                    Thread.sleep(2500) // let WhatsApp hand the message to the network
                    return Res(true)
                }
                Thread.sleep(400)
            }
        }
        return Res(false, "الرسالة ما اتبعتتش")
    }

    // ---------------- intents / files ----------------

    private fun pickPkg(ctx: Context): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
        }

    private fun prepareFile(ctx: Context): File? {
        val att = Store.attachment() ?: return null
        val data = att.optString("data", "")
        if (!data.contains(",")) return null
        val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
        val cleaned = att.optString("name", "file").replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val name = cleaned.trim('.').ifEmpty { "file" }
        val dir = File(ctx.cacheDir, "share")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        ShareProvider.mime = att.optString("type", "").ifEmpty { "application/octet-stream" }
        return f
    }

    private fun buildIntent(ctx: Context, pkg: String, job: Job): Intent {
        if (!job.hasMedia) {
            val uri = Uri.parse("whatsapp://send?phone=${job.phone}&text=" + Uri.encode(job.text))
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.setPackage(pkg)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return i
        }
        val f = prepared ?: throw IllegalStateException("no file")
        val uri = Uri.Builder().scheme("content").authority(ctx.packageName + ".share").appendPath(f.name).build()
        val i = Intent(Intent.ACTION_SEND)
        i.type = ShareProvider.mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        if (job.text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, job.text)
        i.putExtra("jid", job.phone + "@s.whatsapp.net") // opens the preview straight for that contact
        i.setPackage(pkg)
        i.clipData = ClipData.newRawUri("", uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    // ---------------- accessibility helpers ----------------

    private fun find(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var count = 0
        fun dfs(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (++count > 2000 || depth > 45) return null
            if (pred(n)) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val r = dfs(c, depth + 1)
                if (r != null) return r
            }
            return null
        }
        return dfs(root, 0)
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        val hit = byId?.firstOrNull { it.isVisibleToUser }
        if (hit != null) return hit
        return find(root) { n ->
            val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
            d.isNotEmpty() && sendWords.contains(d) && n.isVisibleToUser
        }
    }

    /** Returns the OK button when a dialog about an invalid / unregistered number is showing. */
    private fun invalidDialog(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ok = find(root) { n ->
            val t = (n.text?.toString() ?: "").trim().lowercase()
            n.isClickable && okLabels.contains(t)
        } ?: return null
        val msg = find(root) { n ->
            val t = (n.text?.toString() ?: "").lowercase()
            t.length in 1..200 && invalidHints.any { t.contains(it) }
        }
        return if (msg != null) ok else null
    }

    private fun click(svc: WaService, n: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = n
        var d = 0
        while (cur != null && d < 4) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            cur = cur.parent
            d++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        svc.tap(r.centerX().toFloat(), r.centerY().toFloat())
    }
}
