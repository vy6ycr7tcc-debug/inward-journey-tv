package app.inwardjourney.tv

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update for the launcher itself, served from the public GitHub repo
 * (no auth needed). The repo root holds launcher-version.json:
 *
 *   {"versionCode":5,"versionName":"2.2",
 *    "apkUrl":"https://github.com/<owner>/inward-journey-tv/releases/download/v2.2/inward-journey-tv.apk"}
 *
 * Every release APK is signed with the same local debug key, so installs
 * cleanly over the previous version. Never throws; null means "no update"
 * (or the check failed — stay silent either way).
 */
object LauncherUpdate {
    private const val TAG = "InwardJourneyTv"
    const val VERSION_URL =
        "https://raw.githubusercontent.com/vy6ycr7tcc-debug/inward-journey-tv/main/launcher-version.json"

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun check(versionUrl: String = VERSION_URL): Info? = try {
        val conn = (URL(versionUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        var versionCode = 0
        var versionName = "?"
        var apkUrl = ""
        try {
            JsonReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "versionCode" -> versionCode = reader.nextInt()
                        "versionName" -> {
                            if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                            } else {
                                versionName = reader.nextString()
                            }
                        }
                        "apkUrl" -> {
                            if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                            } else {
                                apkUrl = reader.nextString()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } finally {
            conn.disconnect()
        }
        val info = Info(versionCode, versionName, apkUrl)
        if (info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.startsWith("https://")) info
        else null
    } catch (t: Throwable) {
        Log.w(TAG, "launcher update check failed", t)
        null
    }
}
