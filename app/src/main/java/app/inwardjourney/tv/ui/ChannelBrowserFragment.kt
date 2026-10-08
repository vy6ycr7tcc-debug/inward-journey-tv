package app.inwardjourney.tv.ui

import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import app.inwardjourney.tv.R
import app.inwardjourney.tv.catalog.CatalogRepository
import app.inwardjourney.tv.catalog.Channel
import kotlinx.coroutines.launch

/**
 * Leanback browse surface: one row of channel cards. Loads the catalog on
 * (re)creation — network first, cache/asset fallback — and reports a total
 * load failure to the host activity, which swaps in the error screen.
 */
class ChannelBrowserFragment : BrowseSupportFragment() {

    /** The host starts PlayerActivity for the chosen channel. */
    var onChannelSelected: ((Channel) -> Unit)? = null

    /** Called only when no catalog source at all could be parsed. */
    var onCatalogFailed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        headersState = HEADERS_DISABLED
        title = getString(R.string.browse_title)
        isHeadersTransitionOnBackEnabled = false

        setOnItemViewClickedListener { _, item, _, _ ->
            (item as? Channel)?.let { onChannelSelected?.invoke(it) }
        }

        lifecycleScope.launch {
            val loaded = CatalogRepository(requireContext().applicationContext).loadFresh()
            if (!isAdded) return@launch
            if (loaded == null) {
                onCatalogFailed?.invoke()
                return@launch
            }
            val cards = ArrayObjectAdapter(ChannelCardPresenter()).apply {
                loaded.catalog.channels.forEach { add(it) }
            }
            adapter = ArrayObjectAdapter(ListRowPresenter()).apply {
                add(ListRow(HeaderItem(0, getString(R.string.row_channels)), cards))
            }
        }
    }
}
