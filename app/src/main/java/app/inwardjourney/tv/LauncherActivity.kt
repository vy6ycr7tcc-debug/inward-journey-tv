package app.inwardjourney.tv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Android TV launcher entry point (LEANBACK_LAUNCHER).
 *
 * A tiny, content-agnostic shell: it shows whether game content is installed,
 * lets the user download/update it, and launches [PlayerActivity] to play it.
 * The app itself never bundles the game and never needs updating when the game
 * changes — [GameUpdater] fetches the latest build on demand.
 *
 * UI is built programmatically (no XML layouts) with large, high-contrast text
 * so it is readable from a couch. Native Buttons are D-pad focusable.
 */
class LauncherActivity : Activity() {

    private lateinit var updater: GameUpdater
    private lateinit var statusView: TextView
    private lateinit var versionView: TextView
    private lateinit var messageView: TextView
    private lateinit var playButton: Button
    private lateinit var updateButton: Button
    private lateinit var appUpdateButton: Button
    private lateinit var progressBar: ProgressBar

    private val uiHandler = Handler(Looper.getMainLooper())
    private var busy = false
    private var appUpdateInfo: LauncherUpdate.Info? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = GameUpdater(this)
        // Patch content installed by an older app version (pre-patch).
        updater.ensureTvPatch()
        setContentView(buildUi())
        refreshState()
        checkForAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        // Content may have changed while the player was open.
        refreshState()
    }

    // ---- UI construction ----

    // ---- Game palette (from the Inward Journey style guide) ----
    // Deep night blues, gold accents, warm white — never pure white.
    private val NIGHT_TOP = Color.parseColor("#12122B")
    private val NIGHT_BOTTOM = Color.parseColor("#050509")
    private val MOON_GLOW = Color.parseColor("#3A3670")
    private val WARM_WHITE = Color.parseColor("#F5E9D2")
    private val GOLD = Color.parseColor("#E9C37D")
    private val PALE_BLUE = Color.parseColor("#B8D1FF")
    private val MUTED_BLUE = Color.parseColor("#8E8AA8")
    private val BUTTON_BG = Color.parseColor("#14142C")
    private val BUTTON_BORDER = Color.parseColor("#3D3D66")
    private val BUTTON_FOCUSED_BG = Color.parseColor("#3A2E18")
    private val SERIF: Typeface get() = Typeface.create("serif", Typeface.NORMAL)

    private fun nightBackground(): LayerDrawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(NIGHT_TOP, NIGHT_BOTTOM)
        )
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.28f)
            gradientRadius = dp(520).toFloat()
            colors = intArrayOf(MOON_GLOW, Color.TRANSPARENT)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    private fun menuButtonBackground(): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(BUTTON_BG)
            setStroke(dp(1), BUTTON_BORDER)
        }
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(BUTTON_FOCUSED_BG)
            setStroke(dp(2), GOLD)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun menuButtonTextColors(): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(WARM_WHITE, PALE_BLUE)
        )

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = nightBackground()
            setPadding(dp(48), dp(56), dp(48), dp(56))
        }

        val title = TextView(this).apply {
            text = "Inward Journey"
            typeface = SERIF
            setTextColor(WARM_WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "a contemplative journey"
            typeface = Typeface.create("serif", Typeface.ITALIC)
            setTextColor(MUTED_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
        }
        root.addView(subtitle)

        val divider = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(GOLD)
                cornerRadius = dp(1).toFloat()
            }
        }
        root.addView(
            divider,
            LinearLayout.LayoutParams(dp(200), dp(2)).apply {
                bottomMargin = dp(20)
            }
        )

        statusView = TextView(this).apply {
            typeface = SERIF
            setTextColor(PALE_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(statusView)

        versionView = TextView(this).apply {
            setTextColor(MUTED_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(versionView)

        messageView = TextView(this).apply {
            setTextColor(MUTED_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(messageView)

        playButton = Button(this).apply {
            text = "Play"
            typeface = SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            letterSpacing = 0.03f
            isFocusable = true
            background = menuButtonBackground()
            setTextColor(menuButtonTextColors())
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { launchPlayer() }
        }
        root.addView(playButton, buttonParams())

        updateButton = Button(this).apply {
            text = "Download / update content"
            typeface = SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            letterSpacing = 0.03f
            isFocusable = true
            background = menuButtonBackground()
            setTextColor(menuButtonTextColors())
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { startUpdate() }
        }
        root.addView(updateButton, buttonParams())

        appUpdateButton = Button(this).apply {
            typeface = SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            letterSpacing = 0.03f
            isFocusable = true
            background = menuButtonBackground()
            setTextColor(menuButtonTextColors())
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE
            setOnClickListener { downloadAndInstallAppUpdate() }
        }
        root.addView(appUpdateButton, buttonParams())

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A2A44"))
            indeterminateTintList = ColorStateList.valueOf(GOLD)
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            }
        )

        return root
    }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---- State ----

    private fun refreshState() {
        val ready = updater.isContentReady()
        playButton.isEnabled = ready && !busy
        val sha = updater.storedSha()
        statusView.text = when {
            ready && sha != null -> "Content: ${shortSha(sha)}"
            ready -> "Content: downloaded"
            else -> "Content: not downloaded"
        }
        versionView.text = "App v${BuildConfig.VERSION_NAME}"
        if (!busy) {
            if (ready) {
                // Start D-pad focus on Play so the remote just works.
                playButton.requestFocus()
            } else {
                // Focus the update button when there's no content yet.
                updateButton.requestFocus()
            }
        }
    }

    // ---- Self-update (the launcher app itself) ----

    /** Quietly checks the GitHub repo for a newer launcher build. */
    private fun checkForAppUpdate() {
        Thread {
            val info = LauncherUpdate.check()
            if (info != null) {
                appUpdateInfo = info
                uiHandler.post {
                    appUpdateButton.text = "Install app update v${info.versionName}"
                    appUpdateButton.visibility = View.VISIBLE
                    messageView.text = "A launcher update is available."
                }
            }
        }.start()
    }

    private fun downloadAndInstallAppUpdate() {
        val info = appUpdateInfo ?: return
        if (busy) return
        busy = true
        playButton.isEnabled = false
        updateButton.isEnabled = false
        appUpdateButton.isEnabled = false
        messageView.text = "Downloading app update…"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        Thread {
            try {
                val out = File(getExternalFilesDir(null), "inward-journey-tv-update.apk")
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                uiHandler.post {
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = pct
                                    messageView.text = "Downloading app update… $pct%"
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                uiHandler.post {
                    progressBar.visibility = View.GONE
                    messageView.text = "Installing… confirm on the system prompt."
                    installApkViaSession(out)
                }
            } catch (t: Throwable) {
                Log.w("InwardJourneyTv", "app update download failed", t)
                uiHandler.post {
                    busy = false
                    progressBar.visibility = View.GONE
                    messageView.text = "App update download failed."
                    refreshState()
                }
            }
        }.start()
    }

    /**
     * Installs an APK via the framework PackageInstaller (no extra
     * dependencies needed). The system still asks the user to confirm, and
     * the "install unknown apps" permission is requested on first use.
     */
    private fun installApkViaSession(apk: File) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                session.openWrite("package", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val action = "$packageName.INSTALL_STATUS"
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                        when (status) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                                }
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (confirm != null) startActivity(confirm)
                            }
                            PackageInstaller.STATUS_SUCCESS -> {
                                // The system restarts into the new version.
                            }
                            else -> uiHandler.post {
                                busy = false
                                messageView.text = "Install failed."
                                refreshState()
                            }
                        }
                        try {
                            unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(receiver, IntentFilter(action))
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    this, sessionId,
                    Intent(action).setPackage(packageName), flags
                )
                session.commit(pi.intentSender)
            } finally {
                session.close()
            }
        } catch (t: Throwable) {
            Log.w("InwardJourneyTv", "install failed", t)
            busy = false
            messageView.text = "Install failed: ${t.message}"
            refreshState()
        }
    }

    private fun shortSha(sha: String): String =
        if (sha.length > 7) sha.substring(0, 7) else sha

    // ---- Actions ----

    private fun launchPlayer() {
        if (!updater.isContentReady()) return
        try {
            startActivity(Intent(this, PlayerActivity::class.java))
        } catch (t: Throwable) {
            messageView.text = "Could not start player: ${t.message}"
        }
    }

    private fun startUpdate() {
        if (busy) return
        busy = true
        playButton.isEnabled = false
        updateButton.isEnabled = false
        messageView.text = "Checking for updates…"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        updater.updateContent(
            progress = { downloaded, total ->
                // progress may arrive on a background thread.
                uiHandler.post {
                    if (total > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = ((downloaded * 100) / total).toInt()
                        messageView.text = "Downloading… ${mb(downloaded)} / ${mb(total)}"
                    } else {
                        progressBar.isIndeterminate = true
                        messageView.text = "Downloading… ${mb(downloaded)}"
                    }
                }
            },
            done = { result ->
                // done always arrives on the main thread.
                busy = false
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                updateButton.isEnabled = true
                when (result) {
                    is GameUpdater.UpdateResult.UpToDate -> {
                        messageView.text = "Already up to date"
                        refreshState()
                    }
                    is GameUpdater.UpdateResult.Updated -> {
                        messageView.text = "Content updated"
                        refreshState()
                        launchPlayer()
                    }
                    is GameUpdater.UpdateResult.Failed -> {
                        messageView.text =
                            "Update failed: ${result.reason} — keeping current version"
                        refreshState()
                    }
                }
            }
        )
    }

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
