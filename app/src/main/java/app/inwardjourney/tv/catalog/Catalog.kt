package app.inwardjourney.tv.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Content catalog (schema v1). The app is a shell: every video URL, thumbnail
 * and channel definition comes from this file — nothing is hardcoded in code.
 */
data class Video(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Int
)

data class Channel(
    val id: String,
    val title: String,
    val description: String,
    val cardImage: String,
    val videos: List<Video>
)

data class ScreensaverConfig(
    val channelId: String?,
    val shuffle: Boolean
)

data class Catalog(
    val version: Int,
    val updated: String,
    val channels: List<Channel>,
    val screensaver: ScreensaverConfig
) {

    fun channel(id: String): Channel? = channels.firstOrNull { it.id == id }

    /** The channel the Daydream plays: configured one, else the first channel. */
    fun screensaverChannel(): Channel? =
        screensaver.channelId?.let { channel(it) } ?: channels.firstOrNull()

    companion object {

        /**
         * Parses catalog JSON. Invalid channels/videos are skipped rather than
         * failing the whole file, so one bad entry can't blank the app.
         *
         * @throws org.json.JSONException if the document itself is malformed
         * @throws IllegalArgumentException if no valid channel remains
         */
        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val channelsArray = root.optJSONArray("channels") ?: JSONArray()
            val channels = buildList {
                for (i in 0 until channelsArray.length()) {
                    val channel = channelsArray.optJSONObject(i) ?: continue
                    val parsed = parseChannel(channel) ?: continue
                    add(parsed)
                }
            }
            if (channels.isEmpty()) {
                throw IllegalArgumentException("catalog contains no valid channels")
            }
            val screensaver = root.optJSONObject("screensaver")
            return Catalog(
                version = root.optInt("version", 1),
                updated = root.optString("updated", ""),
                channels = channels,
                screensaver = ScreensaverConfig(
                    channelId = screensaver?.optString("channelId", "")?.takeIf { it.isNotEmpty() },
                    shuffle = screensaver == null || screensaver.optBoolean("shuffle", true)
                )
            )
        }

        private fun parseChannel(json: JSONObject): Channel? {
            val id = json.optString("id", "").takeIf { it.isNotEmpty() } ?: return null
            val title = json.optString("title", "").takeIf { it.isNotEmpty() } ?: return null
            val videosArray = json.optJSONArray("videos") ?: JSONArray()
            val videos = buildList {
                for (i in 0 until videosArray.length()) {
                    val video = videosArray.optJSONObject(i) ?: continue
                    val url = video.optString("url", "").takeIf { it.isNotEmpty() } ?: continue
                    val videoId = video.optString("id", "").ifEmpty { url }
                    add(
                        Video(
                            id = videoId,
                            title = video.optString("title", videoId),
                            url = url,
                            durationSec = video.optInt("durationSec", 0)
                        )
                    )
                }
            }
            if (videos.isEmpty()) return null
            return Channel(
                id = id,
                title = title,
                description = json.optString("description", ""),
                cardImage = json.optString("cardImage", ""),
                videos = videos
            )
        }
    }
}
