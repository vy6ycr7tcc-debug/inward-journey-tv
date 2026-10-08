package app.inwardjourney.tv.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the channel catalog with a three-step fallback:
 *
 *   1. remote `catalog.json` (cold start only) — cached to internal storage on success
 *   2. the cached copy (used offline)
 *   3. the bundled `assets/catalog-default.json` demo channel
 *
 * Internal cache writes are atomic (write-then-rename) so a crash mid-write
 * can never leave a truncated file behind.
 */
class CatalogRepository(private val context: Context) {

    enum class Source { NETWORK, CACHE, ASSET }

    data class Loaded(val catalog: Catalog, val source: Source)

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

    /** Cold start path: network first, then cache, then bundled default. */
    suspend fun loadFresh(): Loaded? = withContext(Dispatchers.IO) {
        fetchRemote()?.let { raw ->
            runCatching { Catalog.parse(raw) }.getOrNull()?.let { catalog ->
                runCatching {
                    val tmp = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
                    tmp.writeText(raw)
                    tmp.renameTo(cacheFile)
                }
                return@withContext Loaded(catalog, Source.NETWORK)
            }
        }
        loadLocal()
    }

    /** Offline path (player, dream): cached copy first, then bundled default. */
    suspend fun loadLocal(): Loaded? = withContext(Dispatchers.IO) {
        val cached = runCatching { cacheFile.readText() }.getOrNull()
            ?.let { runCatching { Catalog.parse(it) }.getOrNull() }
        if (cached != null) return@withContext Loaded(cached, Source.CACHE)

        val bundled = runCatching {
            context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
            ?.let { runCatching { Catalog.parse(it) }.getOrNull() }
        if (bundled != null) return@withContext Loaded(bundled, Source.ASSET)

        null
    }

    private fun fetchRemote(): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Offline, DNS failure, 5xx — fall back to cache/asset.
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        // The only catalog endpoint the app knows; video URLs live inside it.
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/vy6ycr7tcc-debug/inward-journey-tv/main/catalog.json"
        private const val CACHE_FILE_NAME = "catalog.json"
        private const val ASSET_FILE_NAME = "catalog-default.json"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
