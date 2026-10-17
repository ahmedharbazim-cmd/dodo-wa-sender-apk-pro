package com.dodo.wasender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used to drive the native WhatsApp app:
 * it reads the screen of WhatsApp (only) to find the Send button and taps it.
 */
class WaService : AccessibilityService() {

    companion object {
        @Volatile var instance: WaService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polled by SendEngine */ }

    override fun onInterrupt() { }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        SendEngine.serviceLost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Fallback when ACTION_CLICK isn't available on a node. */
    fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    /**
     * After a message is sent: go back from WhatsApp to this app so the panel
     * (progress + stop button) is visible during the delay. Blocking; call from a worker thread.
     */
    fun returnToApp(waPkg: String) {
        val own = packageName
        var tries = 0
        while (tries++ < 8) {
            val cur = rootInActiveWindow?.packageName?.toString()
            if (cur == own) return
            if (cur == waPkg) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Thread.sleep(900)
            } else {
                Thread.sleep(500)
            }
        }
        if (rootInActiveWindow?.packageName?.toString() != own) {
            try {
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(i)
            } catch (e: Exception) { }
        }
    }
}
