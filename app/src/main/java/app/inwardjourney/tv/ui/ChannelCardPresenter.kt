package app.inwardjourney.tv.ui

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import app.inwardjourney.tv.R
import app.inwardjourney.tv.catalog.Channel
import coil.load

/**
 * Renders one channel as a leanback ImageCard: thumbnail (Coil-loaded from the
 * catalog's cardImage URL) + title, with a bundled gradient placeholder when
 * the catalog has no image or the download fails.
 */
class ChannelCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val card = viewHolder.view as ImageCardView
        card.titleText = channel.title
        card.contentText = channel.description
        val image = card.mainImageView
        if (channel.cardImage.isNotEmpty()) {
            image.load(channel.cardImage) {
                placeholder(R.drawable.card_placeholder)
                error(R.drawable.card_placeholder)
                crossfade(false)
            }
        } else {
            image.setImageResource(R.drawable.card_placeholder)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        // Drop the stale frame; the next bind starts a fresh load (Coil
        // cancels any in-flight request targeting this view automatically).
        card.mainImageView.setImageDrawable(null)
        card.titleText = null
        card.contentText = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}
