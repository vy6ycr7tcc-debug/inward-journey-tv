package app.inwardjourney.tv.dream

import android.graphics.Color
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.inwardjourney.tv.R
import app.inwardjourney.tv.catalog.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Daydream (screensaver): plays the catalog's screensaver channel
 * full-bleed when the TV idles. Non-interactive, no controls, no chrome.
 *
 * Listed under TV Settings -> Screen saver via the DreamService intent filter
 * (see the manifest). The player is built in onDreamingStarted and released
 * in onDreamingStopped — nothing stays resident while the dream is off.
 */
class AmbientDreamService : DreamService() {

    private var container: FrameLayout? = null
    private var player: ExoPlayer? = null
    private var scope: CoroutineScope? = null
    private var dreaming = false

    override fun onCreate() {
        super.onCreate()
        // No touch/keys inside the dream; edge-to-edge fullscreen video.
        setInteractive(false)
        setFullscreen(true)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(messageView(null)) // "loading" — dark screen, never blank-white
        }.also { setContentView(it) }

        val dreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = dreamScope
        dreamScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                CatalogRepository(applicationContext).loadLocal()
            }
            if (!dreaming) return@launch // stopped before the catalog arrived
            startDream(loaded)
        }
    }

    // PlayerView is media3's @UnstableApi surface; opted in locally so the
    // opt-in doesn't propagate to callers of this service.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun startDream(loaded: CatalogRepository.Loaded?) {
        val channel = loaded?.catalog?.screensaverChannel()
        if (channel == null) {
            swapContent(messageView(getString(R.string.dream_unavailable)))
            return
        }

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer

        val playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false // no controls in a dream
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            player = exoPlayer
        }
        swapContent(playerView)

        val items = channel.videos.map { MediaItem.fromUri(it.url) }
        exoPlayer.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.shuffleModeEnabled = loaded.catalog.screensaver.shuffle
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Degraded state: hold a message screen instead of a dead black
                // surface. The dream keeps running until the user exits it.
                swapContent(messageView(getString(R.string.dream_unavailable)))
                releasePlayer()
            }
        })
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun messageView(text: String?): TextView =
        TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.LTGRAY)
            textSize = 24f
            gravity = Gravity.CENTER
            this.text = text ?: ""
        }

    private fun swapContent(view: View) {
        container?.let {
            it.removeAllViews()
            it.addView(view)
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        dreaming = false
        releaseDream()
    }

    override fun onDestroy() {
        releaseDream()
        super.onDestroy()
    }

    private fun releaseDream() {
        scope?.cancel()
        scope = null
        releasePlayer()
        container = null
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
