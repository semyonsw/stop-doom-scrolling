package am.onex.stopdoom.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws the block screen as a window overlay rather than launching an Activity.
 *
 * Two reasons for the overlay. Android 15 tightened background activity launches,
 * so an Activity started from a service is not reliable; and an overlay appears
 * instantly over the feed without a task switch, which matters when the point is
 * to interrupt a reflex.
 *
 * A ComposeView outside an Activity needs a lifecycle, a ViewModelStore and a
 * SavedStateRegistry attached by hand, which is what [OverlayHost] provides.
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var host: OverlayHost? = null

    val isShowing: Boolean get() = host != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("InflateParams")
    fun show(
        title: String,
        reason: String,
        frictionSeconds: Int,
        usedTodaySeconds: Int?,
        replacements: List<String>,
        onDismiss: () -> Unit,
    ) {
        if (!canDrawOverlays()) return
        // Re-showing while already up would restart the friction countdown, which
        // would make the block harder to clear the longer the feed fights back.
        if (host != null) return

        val newHost = OverlayHost(context)
        val view = newHost.createView {
            BlockOverlayContent(
                title = title,
                reason = reason,
                frictionSeconds = frictionSeconds,
                usedTodaySeconds = usedTodaySeconds,
                replacements = replacements,
                onDismiss = {
                    hideIfShowing()
                    onDismiss()
                },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable so the dismiss button works and the overlay takes Back,
            // but not touch-modal, so system gestures still function.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                newHost.onAttached()
                host = newHost
            }
    }

    fun hideIfShowing() {
        val current = host ?: return
        host = null
        runCatching { windowManager.removeView(current.view) }
        current.onDetached()
    }
}

/**
 * Minimal lifecycle scaffolding so Compose can run in a window that is not an
 * Activity. Without these three owners set on the view tree, ComposeView throws
 * as soon as it tries to compose.
 */
private class OverlayHost(private val context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    lateinit var view: View
        private set

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun createView(content: @Composable () -> Unit): View {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setContent { content() }
        }
        view = composeView
        return composeView
    }

    fun onAttached() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDetached() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
