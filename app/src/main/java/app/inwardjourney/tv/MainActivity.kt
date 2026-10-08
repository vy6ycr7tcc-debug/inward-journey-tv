package app.inwardjourney.tv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.inwardjourney.tv.catalog.Channel
import app.inwardjourney.tv.ui.ChannelBrowserFragment
import app.inwardjourney.tv.ui.ErrorFragment
import app.inwardjourney.tv.update.UpdateChecker

/**
 * TV launcher entry: leanback browse screen. On every cold start it also runs
 * the self-update check against version.json.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            showBrowse()
        }
        UpdateChecker(this).check()
    }

    private fun showBrowse() {
        if (isFinishing || isDestroyed) return
        val browse = ChannelBrowserFragment().apply {
            onChannelSelected = { channel -> openPlayer(channel) }
            onCatalogFailed = { showError() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, browse)
            .commitAllowingStateLoss()
    }

    private fun showError() {
        if (isFinishing || isDestroyed) return
        val error = ErrorFragment().apply {
            onRetry = { showBrowse() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, error)
            .commitAllowingStateLoss()
    }

    private fun openPlayer(channel: Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        )
    }
}
