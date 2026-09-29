package app.inwardjourney.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Content-agnostic game storage.
 *
 * The app never bundles the game. Instead it downloads the built site as a zip
 * from [CONTENT_BASE] on demand, unpacks it to internal storage, and the player
 * serves it from disk. A version.json file ({"sha":"<hex>",...}) tells us when a
 * newer build is available.
 *
 * Updates are atomic: the new build is unpacked into `game_new`, validated, and
 * only then renamed over `game`. Any failure leaves the existing copy untouched.
 */
class GameUpdater(private val context: Context) {

    /** Outcome of [updateContent]. */
    sealed class UpdateResult {
        /** Local content already matches the published SHA. */
        object UpToDate : UpdateResult()

        /** A new build was downloaded and swapped in. */
        data class Updated(val newSha: String) : UpdateResult()

        /** The update could not be completed; the previous copy is kept. */
        data class Failed(val reason: String) : UpdateResult()
    }

    companion object {
        private const val TAG = "InwardJourneyTv"
        private const val TV_PATCH_MARKER = "ij-tv-patch-v2"

        // ------------------------------------------------------------------
        // CONTENT SOURCE — repoint this one constant to change where the app
        // fetches version.json and game.zip from.
        // ------------------------------------------------------------------
        const val CONTENT_BASE = "https://vy6ycr7tcc-debug.github.io/Animation"
        // ------------------------------------------------------------------

        private const val VERSION_URL = "$CONTENT_BASE/version.json"
        private const val ZIP_URL = "$CONTENT_BASE/game.zip"

        private const val PREFS = "ij_tv"
        private const val KEY_SHA = "game_sha"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Directory holding the unpacked game (index.html at its root). */
    val gameDir: File get() = File(context.filesDir, "game")

    /** SHA of the currently stored content, or null if none/unknown. */
    fun storedSha(): String? = prefs.getString(KEY_SHA, null)

    /** True when a playable copy of the game is present on disk. */
    fun isContentReady(): Boolean = File(gameDir, "index.html").isFile

    private fun saveSha(sha: String) {
        prefs.edit().putString(KEY_SHA, sha).apply()
    }

    /**
     * Check the published version and, if needed, download and install it.
     *
     * [progress] may be invoked on a background thread (callers should post to
     * the UI thread). [done] is always invoked on the main thread.
     */
    fun updateContent(
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        done: (result: UpdateResult) -> Unit
    ) {
        Thread {
            val result = try {
                doUpdate(progress)
            } catch (t: Throwable) {
                Log.e(TAG, "update failed, keeping current version", t)
                UpdateResult.Failed(t.message ?: t.javaClass.simpleName)
            }
            main.post { done(result) }
        }.start()
    }

    private fun doUpdate(progress: (Long, Long) -> Unit): UpdateResult {
        val remoteSha = fetchVersionSha()
            ?: return UpdateResult.Failed("could not reach content server")

        if (remoteSha == storedSha() && isContentReady()) {
            return UpdateResult.UpToDate
        }

        val zipFile = File(context.cacheDir, "game.zip")
        try {
            download(ZIP_URL, zipFile, progress)

            val newDir = File(context.filesDir, "game_new")
            newDir.deleteRecursively()
            unzip(zipFile, newDir)

            if (!File(newDir, "index.html").isFile) {
                newDir.deleteRecursively()
                throw IOException("downloaded content has no index.html")
            }
            // Cap the render resolution on the on-TV copy before it goes live.
            patchForTv(newDir)

            // Stage the old copy aside first: if the final rename fails we can
            // restore it instead of leaving the player with nothing to load.
            // (Never delete the live dir first — that would make hadOld
            // always false and defeat the rollback.)
            val oldDir = File(context.filesDir, "game_old")
            oldDir.deleteRecursively()
            val hadOld = gameDir.exists()
            if (hadOld && !gameDir.renameTo(oldDir)) {
                throw IOException("could not stage old content")
            }
            if (!newDir.renameTo(gameDir)) {
                if (hadOld) oldDir.renameTo(gameDir) // best-effort restore
                throw IOException("could not swap content dir")
            }
            oldDir.deleteRecursively()
            saveSha(remoteSha)
            Log.i(TAG, "content updated to $remoteSha")
            return UpdateResult.Updated(remoteSha)
        } finally {
            // Never leave a partial download behind.
            zipFile.delete()
        }
    }

    private fun fetchVersionSha(): String? {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            conn.inputStream.bufferedReader().use { r ->
                JsonReader(r).use { reader ->
                    val token = reader.peek()
                    if (token == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "sha") {
                                return reader.nextString()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else if (token == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext() && reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "sha") {
                                    return reader.nextString()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                }
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse version JSON", t)
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            // May be -1 when the server does not send Content-Length.
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        progress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * TV performance patch, applied to the freshly unpacked copy before it
     * goes live. Two parts:
     *
     * 1. Caps window.devicePixelRatio at 0.5 so the renderer draws at half
     *    resolution on a 4K panel — a quarter of the pixels.
     * 2. Forces the game's own "minimum" quality tier and locks it there.
     *    The game has adaptive quality, but it is disabled while the start
     *    map is open (intro mode), so a TV would otherwise sit at the HIGH
     *    tier — bloom, god rays, lake reflections, 2048px shadows — which no
     *    TV SoC can sustain. Minimum drops all of that (512px shadows,
     *    no bloom/rays/reflections, fewer particles).
     *
     * Only the on-TV copy is modified; the published site is untouched. If
     * the game ever ships its own TV mode, this becomes redundant but
     * harmless. Idempotent (marker comment); non-fatal on failure.
     */
    private fun patchForTv(dir: File) {
        val index = File(dir, "index.html")
        if (!index.isFile) return
        try {
            var html = index.readText()
            if (html.contains(TV_PATCH_MARKER)) return
            val headOpen = html.indexOf("<head")
            if (headOpen < 0) return
            val headEnd = html.indexOf('>', headOpen)
            if (headEnd < 0) return
            val patch = "<!-- $TV_PATCH_MARKER --><script>" +
                "try{Object.defineProperty(window,'devicePixelRatio'," +
                "{get:function(){return 0.5},configurable:true});}catch(e){}" +
                "(function(){var n=0;function go(){try{var ij=window.__ij;" +
                "if(ij&&ij.quality){ij.quality.set(3);" +
                "ij.quality.window=function(){};return;}}catch(e){}" +
                "if(++n<120)setTimeout(go,500);}go();})();" +
                "</script>"
            html = html.substring(0, headEnd + 1) + patch + html.substring(headEnd + 1)
            index.writeText(html)
            Log.i(TAG, "applied TV patch")
        } catch (t: Throwable) {
            Log.w(TAG, "TV patch failed (non-fatal)", t)
        }
    }

    /**
     * Applies [patchForTv] to already-installed content (e.g. downloaded by
     * an older app version before the patch existed). Safe to call on every
     * launch: it no-ops when the marker is present.
     */
    fun ensureTvPatch() {
        if (isContentReady()) patchForTv(gameDir)
    }

    private fun unzip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val base = destDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                // Zip-slip guard: never write outside destDir.
                if (!out.canonicalPath.startsWith(base)) {
                    throw IOException("bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
