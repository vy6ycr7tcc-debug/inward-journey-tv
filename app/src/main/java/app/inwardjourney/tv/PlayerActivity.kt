package app.inwardjourney.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.inwardjourney.tv.catalog.CatalogRepository
import app.inwardjourney.tv.catalog.Channel
import kotlinx.coroutines.launch

/**
 * Fullscreen ambient playback.
 *
 * - Single-video and multi-video channels both loop via REPEAT_MODE_ALL:
 *   one item loops in place, a playlist wraps with no end-of-list gap.
 * - D-pad: OK toggles play/pause (and surfaces the overlay), left/right seek
 *   to the previous/next video, Back returns to the browse screen.
 * - The overlay shows channel + video title, fades after a few idle seconds,
 *   any keypress brings it back.
 * - Audio focus is delegated to ExoPlayer: focus loss pauses playback.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlayPanel: LinearLayout
    private lateinit var overlayChannel: TextView
    private lateinit var overlayVideo: TextView
    private lateinit var overlayState: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var btnRetry: Button

    private var player: ExoPlayer? = null
    private var channel: Channel? = null
    private var pausedByUser = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        if (player?.playWhenReady == true) fadeOutOverlay()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateKeepScreenOn()
            updateOverlay()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateKeepScreenOn()
            updateOverlay()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateOverlay()
        }

        override fun onPlayerError(error: PlaybackException) {
            showError(getString(R.string.player_error_message))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — the video content is the star.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        overlayPanel = findViewById(R.id.overlayPanel)
        overlayChannel = findViewById(R.id.overlayChannel)
        overlayVideo = findViewById(R.id.overlayVideo)
        overlayState = findViewById(R.id.overlayState)
        errorPanel = findViewById(R.id.errorPanel)
        errorMessage = findViewById(R.id.errorMessage)
        btnRetry = findViewById(R.id.btnPlayerRetry)
        btnRetry.setOnClickListener { onRetry() }

        loadChannel()
    }

    private fun loadChannel() {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            val loaded = CatalogRepository(applicationContext).loadLocal()
            val catalog = loaded?.catalog
            val resolved = (channelId?.let { catalog?.channel(it) })
                ?: catalog?.channels?.firstOrNull()
            if (catalog == null || resolved == null) {
                showError(getString(R.string.channel_missing))
            } else {
                startPlayback(resolved)
            }
        }
    }

    private fun startPlayback(channel: Channel) {
        this.channel = channel

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true // pauses when audio focus is lost
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer
        playerView.player = exoPlayer

        val items = channel.videos.map { video ->
            MediaItem.Builder()
                .setUri(video.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).build())
                .build()
        }
        exoPlayer.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        // The whole point of an ambient app: seamless looping, no end-of-list.
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.addListener(playerListener)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        updateOverlay()
        showOverlay()
    }

    // --- D-pad -------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                togglePlayPause()
                showOverlay()
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showOverlay()
                seekBy(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showOverlay()
                seekBy(1)
                true
            }

            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }

            else -> {
                showOverlay()
                super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun togglePlayPause() {
        val player = player ?: return
        pausedByUser = player.playWhenReady
        player.playWhenReady = !player.playWhenReady
        updateOverlay()
    }

    private fun seekBy(delta: Int) {
        val player = player ?: return
        val count = player.mediaItemCount
        if (count <= 1) return
        // Manual wrap so prev/next cycle the channel regardless of Media3
        // repeat-mode edge behavior at the list boundaries.
        val next = (player.currentMediaItemIndex + delta + count) % count
        player.seekTo(next, 0L)
    }

    private fun onRetry() {
        if (player == null) {
            // Channel never resolved (catalog load failure) — reload.
            errorPanel.isVisible = false
            loadChannel()
            return
        }
        errorPanel.isVisible = false
        pausedByUser = false
        player?.prepare()
        player?.playWhenReady = true
        showOverlay()
    }

    // --- Overlay -----------------------------------------------------------

    private fun updateOverlay() {
        val channel = channel ?: return
        val player = player ?: return
        overlayChannel.text = channel.title
        val video = channel.videos.getOrNull(player.currentMediaItemIndex) ?: return
        overlayVideo.text = video.title
        val paused = !player.playWhenReady
        overlayState.isVisible = paused
        if (paused) overlayState.text = getString(R.string.state_paused)
    }

    private fun showOverlay() {
        overlayPanel.animate().cancel()
        overlayPanel.alpha = 1f
        overlayPanel.isVisible = true
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun fadeOutOverlay() {
        overlayPanel.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_MS)
            .withEndAction {
                // Guard: a cancel mid-fade leaves alpha > 0; don't hide then.
                if (overlayPanel.alpha <= 0.01f) overlayPanel.isVisible = false
            }
            .start()
    }

    private fun showError(message: String) {
        errorMessage.text = message
        errorPanel.isVisible = true
        uiHandler.removeCallbacks(hideOverlayRunnable)
        errorPanel.post { btnRetry.requestFocus() }
    }

    private fun updateKeepScreenOn() {
        val playing = player?.let {
            it.playWhenReady && it.playbackState != Player.STATE_IDLE
        } ?: false
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --- Lifecycle ---------------------------------------------------------

    override fun onStart() {
        super.onStart()
        if (!pausedByUser) player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(hideOverlayRunnable)
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        player?.let { it.removeListener(playerListener); it.release() }
        player = null
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        private const val OVERLAY_HIDE_DELAY_MS = 4_000L
        private const val OVERLAY_FADE_MS = 300L
    }
}
