package app.inwardjourney.tv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * Fullscreen WebView shell for Inward Journey on Android TV.
 *
 * Local-first: the game is served from disk via
 * [WebViewClient.shouldInterceptRequest], so loads are instant and offline.
 * The launcher ([LauncherActivity]) owns content downloads; this activity never
 * checks for updates.
 *
 * Remote: D-pad arrows move (the page handles them), SELECT taps Space
 * (the game's action button), holding SELECT sends F (fly), and the
 * play/pause key also acts as Space. BACK steps through page history.
 */
class PlayerActivity : Activity() {

    companion object {
        private const val TAG = "InwardJourneyTv"

        /** Page URL. The game files themselves are served from disk (see below). */
        private const val GAME_URL = "https://vy6ycr7tcc-debug.github.io/Animation/"

        private const val GAME_HOST = "vy6ycr7tcc-debug.github.io"
        private const val GAME_PATH_PREFIX = "Animation"

        /** Holding SELECT this long sends F (fly) instead of Space (action). */
        private const val SELECT_LONG_PRESS_MS = 450L

        private val RANGE_HEADER_REGEX = Regex("bytes=(\\d*)-(\\d*)")
    }

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var errorView: TextView
    private lateinit var updater: GameUpdater

    // DIAG-OVERLAY — remove before release
    private lateinit var diagOverlay: TextView
    private var lastKeyCode: Int = 0
    private val backPressTimes = LongArray(3)

    private val uiHandler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { finish() }
    private var selectLongFired = false
    private val selectLongRunnable = Runnable {
        selectLongFired = true
        sendKeyToPage(KeyEvent.KEYCODE_F, KeyEvent.ACTION_DOWN)
        sendKeyToPage(KeyEvent.KEYCODE_F, KeyEvent.ACTION_UP)
    }

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
            updater = GameUpdater(this)

            if (!updater.isContentReady()) {
                // Shouldn't happen — the launcher gates Play on content being ready.
                statusView.text = "No content downloaded. Go back and download content."
                return
            }

            statusView.text = "Preparing game…"
            initWebView(root)
        } catch (t: Throwable) {
            Log.e(TAG, "Startup failed", t)
            statusView.text =
                "The viewer couldn't start on this TV.\n\n${t.javaClass.name}\n${t.message}"
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
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // DIAG-OVERLAY — remove before release
        diagOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 14f
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
            text = "Diag Ready"
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
        root.addView(
            diagOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 32
                marginEnd = 32
            }
        )
        // The remote's keys must reach the page: keep the WebView focused.
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {
            /**
             * Serve the game's files from disk. The page URL stays https, so
             * the game behaves exactly as it does online; only the bytes come
             * from local storage. Anything missing locally falls through to
             * the network. version.json / game.zip always come from the
             * network so the launcher's update check sees the live site.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                try {
                    val url = request.url
                    if (url.scheme == "https" && url.host == GAME_HOST) {
                        val segs = url.pathSegments
                        if (segs.isNotEmpty() && segs[0] == GAME_PATH_PREFIX && ::updater.isInitialized) {
                            val rel = segs.drop(1).joinToString("/")
                            if (rel == "version.json" || rel == "game.zip") return null
                            val base = updater.gameDir.canonicalPath + File.separator
                            val file = File(updater.gameDir, if (rel.isEmpty()) "index.html" else rel)
                            if (file.canonicalPath.startsWith(base) && file.isFile) {
                                val (mime, enc) = mimeType(file.name)
                                // Audio/video players request byte ranges when seeking.
                                val range = request.requestHeaders["Range"]
                                if (range != null) {
                                    partialResponse(file, mime, enc, range)?.let { return it }
                                }
                                return WebResourceResponse(mime, enc, file.inputStream())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "intercept failed", t)
                }
                return null
            }

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
        }

        val urlToLoad = if (GAME_URL.contains("?")) {
            "$GAME_URL&tv=1"
        } else {
            "$GAME_URL?tv=1"
        }
        webView.loadUrl(urlToLoad)
    }

    private fun mimeType(name: String): Pair<String, String?> {
        val text = "UTF-8"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html" to text
            "js", "mjs" -> "text/javascript" to text
            "css" -> "text/css" to text
            "json" -> "application/json" to text
            "svg" -> "image/svg+xml" to text
            "txt" -> "text/plain" to text
            "png" -> "image/png" to null
            "jpg", "jpeg" -> "image/jpeg" to null
            "webp" -> "image/webp" to null
            "gif" -> "image/gif" to null
            "ico" -> "image/x-icon" to null
            "mp3" -> "audio/mpeg" to null
            "ogg", "oga" -> "audio/ogg" to null
            "wav" -> "audio/wav" to null
            "mp4" -> "video/mp4" to null
            "webm" -> "video/webm" to null
            "wasm" -> "application/wasm" to null
            "glb" -> "model/gltf-binary" to null
            "gltf" -> "model/gltf+json" to text
            "woff2" -> "font/woff2" to null
            "woff" -> "font/woff" to null
            "ttf" -> "font/ttf" to null
            else -> "application/octet-stream" to null
        }
    }

    /**
     * Serve a 206 Partial Content response so media players can seek.
     * Returns null when the Range header can't be satisfied (falls back to
     * the full 200 response).
     */
    private fun partialResponse(
        file: File,
        mime: String,
        encoding: String?,
        rangeHeader: String
    ): WebResourceResponse? {
        return try {
            val m = RANGE_HEADER_REGEX.find(rangeHeader) ?: return null
            val size = file.length()
            if (size <= 0) return null
            var start = m.groupValues[1].toLongOrNull() ?: 0L
            var end = m.groupValues[2].toLongOrNull() ?: (size - 1)
            if (end >= size) end = size - 1
            if (start > end || start >= size) {
                return WebResourceResponse(
                    "text/plain", "UTF-8", 416, "Range Not Satisfiable",
                    mapOf("Content-Range" to "bytes */$size"), null
                )
            }
            val raf = java.io.RandomAccessFile(file, "r")
            raf.seek(start)
            val length = end - start + 1
            val rawStream = object : java.io.InputStream() {
                var remaining = length
                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val b = raf.read()
                    if (b >= 0) remaining--
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
                    if (n > 0) remaining -= n
                    return n
                }

                override fun close() {
                    raf.close()
                }
            }
            val bufferedStream = java.io.BufferedInputStream(rawStream)
            WebResourceResponse(
                mime, encoding, 206, "Partial Content",
                mapOf(
                    "Content-Range" to "bytes $start-$end/$size",
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to length.toString()
                ),
                bufferedStream
            )
        } catch (t: Throwable) {
            Log.w(TAG, "range request failed", t)
            null
        }
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

    // ---- Remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastKeyCode = event.keyCode
            val diagText = "key: $lastKeyCode"
            Log.d("InwardJourneyTv", diagText)
            if (::diagOverlay.isInitialized && diagOverlay.visibility == View.VISIBLE) {
                val currentText = diagOverlay.text.toString()
                val parts = currentText.split(" | el: ")
                if (parts.size == 2) {
                    diagOverlay.text = "key: $lastKeyCode | el: ${parts[1]}"
                } else {
                    diagOverlay.text = diagText
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendKeyToPage(keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0))
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = SystemClock.uptimeMillis()
            backPressTimes[0] = backPressTimes[1]
            backPressTimes[1] = backPressTimes[2]
            backPressTimes[2] = now
            if (backPressTimes[2] - backPressTimes[0] < 1500) {
                diagOverlay.visibility = if (diagOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                backPressTimes.fill(0)
                uiHandler.removeCallbacks(exitRunnable)
                return true
            }
            // BACK steps through WebView history; exits only at the root page.
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                uiHandler.removeCallbacks(exitRunnable)
                uiHandler.postDelayed(exitRunnable, 1600)
            }
            return true
        }
        if (::webView.isInitialized && event != null) {
            if (isSelectKey(keyCode)) {
                // Tap = Space (game action). Hold = F (fly). Swallow repeats.
                if (event.repeatCount == 0) {
                    selectLongFired = false
                    uiHandler.postDelayed(selectLongRunnable, SELECT_LONG_PRESS_MS)
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (event.repeatCount == 0) {
                    sendKeyToPage(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_DOWN)
                }
                return true
            }
        }
        // D-pad arrows and everything else fall through to the focused WebView.
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        if (::webView.isInitialized && event != null) {
            if (isSelectKey(keyCode)) {
                uiHandler.removeCallbacks(selectLongRunnable)
                if (!selectLongFired) activateFocusedOrGameAction()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                sendKeyToPage(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * SELECT tap. If a real control (button/link/input) has DOM focus — e.g.
     * the start map's place buttons — click it directly. Otherwise fall back
     * to the Space key the 3D game reads as its action button.
     *
     * Space alone cannot activate the map buttons: the game's intro key
     * handler preventDefaults Space while the map is open, so the click never
     * fires. Clicking the focused element skips that trap entirely.
     */
    private fun activateFocusedOrGameAction() {
        val js = """
            (function(){
                function getDesc(e) {
                    if (!e) return "none";
                    var d = e.tagName.toLowerCase();
                    if (e.id) d += "#" + e.id;
                    if (e.className && typeof e.className === 'string') d += "." + e.className.replace(/\s+/g, '.');
                    return d;
                }
                var el = document.activeElement;
                var hasFocus = !!el && el !== document.body && /^(BUTTON|A|INPUT|SELECT|TEXTAREA)$/.test(el.tagName);
                if (hasFocus) {
                    el.click();
                    return "1|" + getDesc(el);
                }
                var sel = 'button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
                var fallbacks = document.querySelectorAll(sel);
                for (var i = 0; i < fallbacks.length; i++) {
                    var f = fallbacks[i];
                    if (!f.disabled && f.offsetWidth > 0 && f.offsetHeight > 0 && window.getComputedStyle(f).visibility !== 'hidden') {
                        f.focus();
                        f.click();
                        return "1|" + getDesc(f);
                    }
                }
                return "0|" + getDesc(document.activeElement);
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            val unquoted = if (result != null && result.length >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
                result.substring(1, result.length - 1).replace("\\\"", "\"")
            } else {
                result ?: ""
            }

            val clicked = unquoted.startsWith("1|")
            val activeDesc = if (unquoted.contains("|")) unquoted.substringAfter("|") else "none"

            if (!clicked) {
                sendKeyToPage(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_DOWN)
                sendKeyToPage(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP)
            }

            val diagText = "key: $lastKeyCode | el: $activeDesc"
            Log.d("InwardJourneyTv", diagText)
            if (::diagOverlay.isInitialized) {
                diagOverlay.text = diagText
            }
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.requestFocus()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(selectLongRunnable)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
