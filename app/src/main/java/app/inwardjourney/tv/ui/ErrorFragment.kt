package app.inwardjourney.tv.ui

import android.os.Bundle
import android.view.View
import androidx.leanback.app.ErrorSupportFragment
import app.inwardjourney.tv.R

/**
 * The authentic leanback error screen (title + message + Retry button).
 * Shown only when no catalog source at all could be parsed — the app never
 * shows a blank screen and Retry re-runs the full load chain.
 */
class ErrorFragment : ErrorSupportFragment() {

    var onRetry: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit method calls: leanback's getter/setter types don't always
        // match for Kotlin synthetic properties.
        setTitle(getString(R.string.error_title))
        setMessage(getString(R.string.error_message))
        setButtonText(getString(R.string.action_retry))
        setButtonClickListener(View.OnClickListener { onRetry?.invoke() })
    }
}
