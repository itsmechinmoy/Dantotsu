package ani.dantotsu.media.anime

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.mediarouter.app.MediaRouteActionProvider
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import ani.dantotsu.R

class CustomCastProvider(context: Context) : MediaRouteActionProvider(context) {
    init {
        dialogFactory = CustomCastThemeFactory()
    }
}

class CustomCastThemeFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment {
        return CustomMediaRouterChooserDialogFragment()
    }

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment {
        return CustomMediaRouteControllerDialogFragment()
    }
}

class CustomMediaRouterChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(
        context: Context,
        savedInstanceState: Bundle?
    ): MediaRouteChooserDialog =
        MediaRouteChooserDialog(context, R.style.MyPopup)
}

class CustomMediaRouteControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(
        context: Context,
        savedInstanceState: Bundle?
    ): MediaRouteControllerDialog =
        MediaRouteControllerDialog(context, R.style.MyPopup)
}

class CustomCastButton @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.mediarouter.app.MediaRouteButton(context, attrs, defStyleAttr) {
    private var callback: (() -> Unit)? = null
    private var forceAlwaysVisible: Boolean = false

    override fun setAlwaysVisible(alwaysVisible: Boolean) {
        this.forceAlwaysVisible = alwaysVisible
        try {
            super.setAlwaysVisible(alwaysVisible)
        } catch (_: Throwable) {}
        if (alwaysVisible) {
            visibility = View.VISIBLE
        }
    }

    fun setCastCallback(cb: () -> Unit) {
        this.callback = cb
    }

    override fun setVisibility(visibility: Int) {
        if (forceAlwaysVisible && visibility != View.VISIBLE) {
            super.setVisibility(View.VISIBLE)
            return
        }
        super.setVisibility(visibility)
    }

    override fun performClick(): Boolean {
        callback?.let {
            it.invoke()
            return true
        }
        return super.performClick()
    }
}
