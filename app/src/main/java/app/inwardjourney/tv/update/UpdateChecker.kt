package app.inwardjourney.tv.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.inwardjourney.tv.BuildConfig
import app.inwardjourney.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cold-start self-update (same pattern as the previous launcher):
 *
 * 1. fetch version.json from the repo's main branch
 * 2. if remote versionCode > installed BuildConfig.VERSION_CODE, offer the
 *    update in a D-pad friendly dialog
 * 3. on accept, download the APK to cache and hand it to the system installer
 *    via FileProvider (REQUEST_INSTALL_PACKAGES)
 *
 * A failed check is silent — the app must always reach the browse screen.
 */
class UpdateChecker(private val activity: Activity) {

    private data class RemoteUpdate(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String
    )

    private var shown = false

    fun check() {
        if (shown || activity.isFinishing || activity.isDestroyed) return
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) { fetchUpdate() } ?: return@launch
            if (update.versionCode <= BuildConfig.VERSION_CODE) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            showUpdateDialog(update)
        }
    }

    private fun fetchUpdate(): RemoteUpdate? {
        var connection: HttpURLConnection? = null
        val json = try {
            connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
        return try {
            val obj = JSONObject(json)
            val apkUrl = obj.getString("apkUrl")
            if (!apkUrl.startsWith("https://")) return null
            RemoteUpdate(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.optString("versionName", ""),
                apkUrl = apkUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun showUpdateDialog(update: RemoteUpdate) {
        shown = true
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title))
            .setMessage(activity.getString(R.string.update_message, update.versionName))
            .setPositiveButton(activity.getString(R.string.update_action)) { _, _ ->
                downloadAndInstall(update)
            }
            .setNegativeButton(activity.getString(R.string.update_later), null)
            .show()
    }

    private fun downloadAndInstall(update: RemoteUpdate) {
        val owner = activity as? LifecycleOwner ?: return
        val progress = runCatching {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_downloading))
                .setCancelable(false)
                .show()
        }.getOrNull() ?: return

        owner.lifecycleScope.launch {
            val apk = try {
                withContext(Dispatchers.IO) { download(update.apkUrl) }
            } finally {
                runCatching { if (progress.isShowing) progress.dismiss() }
            }
            if (apk == null) {
                Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            install(apk)
        }
    }

    private fun download(url: String): File? {
        return try {
            val dir = File(activity.cacheDir, "update")
            dir.mkdirs()
            val target = File(dir, "inward-journey.apk")
            if (target.exists()) target.delete()

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            target.takeIf { it.length() > 0L }
        } catch (e: Exception) {
            null
        }
    }

    private fun install(apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package.archive")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // The only update endpoint the app knows; apkUrl comes from the file.
        const val VERSION_URL =
            "https://raw.githubusercontent.com/vy6ycr7tcc-debug/inward-journey-tv/main/version.json"
    }
}
