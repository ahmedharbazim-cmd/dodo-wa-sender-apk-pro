package com.dodo.wasender

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var panel: WebView
    private lateinit var permBtn: Button
    private var chooserCb: ValueCallback<Array<Uri>>? = null

    inner class Bridge {
        @JavascriptInterface
        fun get(keysJson: String): String = Store.get(keysJson)

        @JavascriptInterface
        fun set(objJson: String) = Store.set(objJson)

        @JavascriptInterface
        fun remove(keysJson: String) = Store.remove(keysJson)

        /** Called by the panel's Start / Resume buttons. */
        @JavascriptInterface
        fun openWA() {
            runOnUiThread { beginSend() }
        }

        @JavascriptInterface
        fun saveCsv(text: String) {
            try {
                var where = "Downloads"
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "wa-results.csv")
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                } else {
                    // Android 9 and older: save in the app's own folder (no storage permission needed)
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    val f = File(dir, "wa-results.csv")
                    f.writeText(text, Charsets.UTF_8)
                    where = f.absolutePath
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "تم الحفظ في $where", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "تعذر الحفظ", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.parseColor("#12332D"))
        permBtn = Button(this)
        val appBtn = Button(this)
        appBtn.text = "إعدادات التطبيق"
        bar.addView(permBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(appBtn, LinearLayout.LayoutParams(0, wrap, 1f))

        panel = WebView(this)
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(panel, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        setupPanel()
        permBtn.setOnClickListener { openAccessibilitySettings() }
        // On Android 13+ a sideloaded app needs "Allow restricted settings" (⋮ menu in App info)
        appBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
        }
        refreshPermButton()
    }

    override fun onResume() {
        super.onResume()
        refreshPermButton()
        // the service connects a moment after the user turns it on
        permBtn.postDelayed({ refreshPermButton() }, 1000)
    }

    private fun refreshPermButton() {
        val on = WaService.instance != null
        permBtn.text = if (on) "الإذن مفعّل ✅" else "تفعيل إذن الإرسال ❌"
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "افتح الإعدادات ← إمكانية الوصول ← DoDo WA Sender", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginSend() {
        if (WaService.instance == null) {
            Store.updateState { st ->
                st.put("running", false)
                st.put("status", "فعّل إذن الإرسال الأول (الزر فوق) وبعدين اضغط ابدأ")
            }
            Toast.makeText(
                this,
                "فعّل خدمة DoDo WA Sender من إمكانية الوصول. لو الزر رمادي: إعدادات التطبيق ← ⋮ ← السماح بالإعدادات المقيّدة",
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }
        SendEngine.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPanel() {
        val s = panel.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // The panel is a local page only: no file-system / content access, no external pages.
        // (file:///android_asset keeps working; it is not governed by allowFileAccess.)
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION") s.setAllowFileAccessFromFileURLs(false)
        @Suppress("DEPRECATION") s.setAllowUniversalAccessFromFileURLs(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setGeolocationEnabled(false)
        panel.addJavascriptInterface(Bridge(), "AndroidStore")
        panel.webViewClient = object : WebViewClient() {
            // the JS bridge must never be reachable from anything but our own page
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return true
                return !u.startsWith("file:///android_asset/")
            }
        }
        panel.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams!!.createIntent(), 77)
                    true
                } catch (e: Exception) {
                    chooserCb = null
                    false
                }
            }
        }
        panel.loadUrl("file:///android_asset/panel/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooserCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
