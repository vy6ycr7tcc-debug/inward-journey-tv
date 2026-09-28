package app.inwardjourney.tv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Fullscreen WebView shell for Inward Journey on Android TV.
 *
 * Crash-proof startup: the status view is shown first, and every later
 * step runs inside try/catch. If the WebView (or anything else) fails,
 * the TV shows the actual error instead of dying silently.
 *
 * Set DIAG_MODE = true to run the on-device capability check
 * (assets/diag.html) instead of the game.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "InwardJourneyTv"

        /** Live game URL. Swap this if the production URL changes. */
        private const val GAME_URL = "https://vy6ycr7tcc-debug.github.io/Animation/"

        /** true -> load the capability diagnostic instead of the game. */
        private const val DIAG_MODE = false

        private const val DIAG_URL = "file:///android_asset/diag.html"
    }

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var errorView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            text = "Starting Inward Journey…"
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Status is on screen BEFORE anything that can throw.
        setContentView(root)

        try {
            makeFullscreen()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val pkg = WebView.getCurrentWebViewPackage()
            statusView.text =
                "Starting Inward Journey…\nWebView: ${pkg?.packageName ?: "?"} ${pkg?.versionName ?: "?"}"
            initWebView(root)
        } catch (t: Throwable) {
            Log.e(TAG, "Startup failed", t)
            statusView.text =
                "The viewer couldn't start on this TV.\n\n${t.javaClass.name}\n${t.message}" +
                    "\n\nTry updating Android System WebView from the Play Store, then reopen."
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun initWebView(root: FrameLayout) {
        if (BuildConfig.DEBUG) {
            // Inspect from a PC via chrome://inspect while USB/adb connected.
            WebView.setWebContentsDebuggingEnabled(true)
        }

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(64, 64, 64, 64)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // WebView covers the status view; error overlay sits on top of both.
        root.addView(webView, 1)
        root.addView(
            errorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        webView.addJavascriptInterface(TvBridge(), "TvBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showError("Could not load the game.\n\n${error.description}\n\nURL: ${request.url}")
                }
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            // WebGPU needs a recent System WebView (121+). The diag page verifies.
        }

        webView.loadUrl(if (DIAG_MODE) DIAG_URL else GAME_URL)
    }

    private fun makeFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            errorView.text = msg
            errorView.visibility = View.VISIBLE
            Log.e(TAG, msg)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK steps through WebView history; exits only at the root page.
        if (keyCode == KeyEvent.KEYCODE_BACK && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        // D-pad arrows / SELECT fall through to the WebView and the page.
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** JS bridge used by assets/diag.html. */
    private inner class TvBridge {
        @JavascriptInterface
        fun report(json: String) {
            Log.i(TAG, "DIAG $json")
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Diagnostics captured (logcat tag: InwardJourneyTv)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
