package app.inwardjourney.tv

import android.util.Log
import org.json.JSONObject
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
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val j = JSONObject(body)
        val info = Info(
            j.getInt("versionCode"),
            j.optString("versionName", "?"),
            j.getString("apkUrl")
        )
        if (info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.startsWith("https://")) info
        else null
    } catch (t: Throwable) {
        Log.w(TAG, "launcher update check failed", t)
        null
    }
}
