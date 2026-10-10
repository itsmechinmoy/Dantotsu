package ani.dantotsu.media.manga.mangareader.webgpu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.transition.Transition
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp

object WebGpuManager {

    /**
     * Checks if the current device and Android OS version support WebGPU hardware acceleration
     * (requires Vulkan 1.1+ on Android 10+).
     */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if WebGPU renderer is supported by hardware/OS AND enabled in user settings.
     */
    fun isEnabled(context: Context): Boolean {
        if (!isSupported(context)) return false
        return try {
            ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.WebGpuRenderer)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Safely creates an [ImageView] instance for paged reading mode.
     * Returns null if device or runtime does not support WebGPU.
     */
    fun createPagedViewer(context: Context, isVertical: Boolean = false, isReversed: Boolean = false): ImageView? {
        if (!isSupported(context)) return null
        return try {
            ImageView(context, isVertical = isVertical, isReversed = isReversed)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Safely creates an [ImageViewContinuous] instance for continuous reading mode.
     * Returns null if device or runtime does not support WebGPU.
     */
    fun createContinuousViewer(context: Context): ImageViewContinuous? {
        if (!isSupported(context)) return null
        return try {
            ImageViewContinuous(context)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the appropriate transition animation based on preference or mode.
     */
    fun getTransition(name: String, isVertical: Boolean = false): Transition {
        return when (name.lowercase()) {
            "cube" -> TransitionCube
            "flip" -> TransitionFlip
            "sphere" -> TransitionSphere
            "fade" -> TransitionFade
            "stack_left" -> TransitionStackLeft
            "stack_right" -> TransitionStackRight
            "stack_up" -> TransitionStackUp
            "stack_down" -> TransitionStackDown
            "none" -> TransitionNone
            else -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
        }
    }
}
