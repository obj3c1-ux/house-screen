package com.house.screen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen page viewer for the house display.
 *  - opens <address>/display?k=<pairing code>
 *  - keeps the screen on, hides the system bars
 *  - retries by itself when Wi-Fi drops or the page fails, and reloads if the page stops running
 *  - press MENU (the three-line button) on the remote to change the address or code
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var failed = false
    private var dialogOpen = false

    private val prefs get() = getSharedPreferences("house", Context.MODE_PRIVATE)
    private var base: String
        get() = prefs.getString("base", null) ?: BuildConfig.DEFAULT_BASE
        set(v) = prefs.edit().putString("base", v).apply()
    private var code: String
        get() = prefs.getString("code", "") ?: ""
        set(v) = prefs.edit().putString("code", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        failed = true
                        handler.postDelayed({ load() }, 10_000)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!failed) return
                    // a successful load after a failure
                    failed = false
                }
            }
        }
        setContentView(web)
        hideBars()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { if (failed) load() }
            }
        })

        if (code.isEmpty()) showSettings() else load()
        watchdog()
    }

    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun load() {
        if (code.isEmpty() || base.isEmpty()) return
        failed = false
        web.loadUrl(base.trimEnd('/') + "/display?k=" + code.trim())
    }

    /** Every 5 minutes, make sure the page is really running; if not, load it again. */
    private fun watchdog() {
        handler.postDelayed({
            if (!dialogOpen && code.isNotEmpty()) {
                web.evaluateJavascript("typeof D !== 'undefined'") { r -> if (r != "true") load() }
            }
            watchdog()
        }, 5 * 60_000)
    }

    private fun showSettings() {
        if (dialogOpen) return
        dialogOpen = true
        fun field(hint: String, value: String) = EditText(this).apply {
            setText(value); this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val addr = field("Worker address, e.g. https://name.workers.dev", base)
        val pair = field("Pairing code (from the dashboard, Settings > Screen)", code)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(context).apply { text = "Worker address" })
            addView(addr)
            addView(TextView(context).apply { text = "Pairing code" })
            addView(pair)
        }
        AlertDialog.Builder(this)
            .setTitle("House Screen")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                base = addr.text.toString().trim()
                code = pair.text.toString().trim()
                load()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { dialogOpen = false; hideBars() }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showSettings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
    }
}
