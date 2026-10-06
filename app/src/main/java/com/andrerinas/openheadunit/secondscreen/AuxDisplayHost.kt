package com.andrerinas.openheadunit.secondscreen

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.view.AuxDisplayPresentation
import com.andrerinas.openheadunit.view.AuxPictureView

/**
 * The window on the Android display that carries the second screen.
 *
 * With the overlay permission it is an application overlay the service opens when a session starts
 * and closes when it ends, so the second screen keeps running while the projection activity is
 * hidden or gone. Without it, the projection activity hosts a Presentation as before, since a
 * Presentation shown from a service needs that same permission.
 *
 * The overlay is added straight through the display's WindowManager rather than by retyping a
 * Presentation: from Android 12 a Presentation's window context is created for its own type, and
 * changing the type afterwards is refused.
 *
 * Main thread only; [showForSession] and [dismissForSession] post themselves there.
 */
object AuxDisplayHost {

    private val main = Handler(Looper.getMainLooper())

    private var presentation: AuxDisplayPresentation? = null
    private var overlay: AuxPictureView? = null
    private var overlayManager: WindowManager? = null

    private val isShowing: Boolean
        get() = overlay != null || presentation?.isShowing == true

    /** The service's half: opens the overlay as a session starts, when the permission allows one. */
    fun showForSession(context: Context, onSurfaceReady: () -> Unit) {
        main.post {
            if (AppPermissions.isOverlayGranted(context)) show(context, onSurfaceReady)
        }
    }

    /** Closes whatever is up as the session ends; the next session announces and opens afresh. */
    fun dismissForSession() {
        main.post { dismiss("the session ended") }
    }

    /**
     * The activity's half: hosts a Presentation when there is no overlay permission. With it the
     * service has already opened the overlay, and this only covers a session already running.
     */
    fun showFromActivity(activity: Activity, sessionLive: Boolean, onSurfaceReady: () -> Unit) {
        // An overlay outlives the activity, so it is only opened for a session that will close it.
        if (AppPermissions.isOverlayGranted(activity) && !sessionLive) return
        show(activity, onSurfaceReady)
    }

    /** As the activity goes away: its Presentation goes with it, the service's overlay stays. */
    fun dismissFromActivity() {
        if (presentation != null) dismiss("the projection activity went away")
    }

    private fun show(context: Context, onSurfaceReady: () -> Unit) {
        val settings = App.provide(context).settings
        if (!settings.auxDisplayEnabled) return
        if (settings.auxOutput != SecondScreenOutputPolicy.Output.ANDROID_DISPLAY) return
        if (isShowing) return
        val display = resolveDisplay(context) ?: run {
            AppLog.w("AuxDisplayHost: no display is attached for the second screen")
            return
        }
        // The size announced for this panel says how much margin each frame carries.
        val panel = SecondScreenHub.announcedTarget
        val (scaleX, scaleY) = if (panel == null) 1f to 1f else AuxDisplayProfilePolicy.marginCropScale(
            AuxDisplayProfilePolicy.profileFor(panel.widthPx, panel.heightPx, panel.densityDpi)
        )
        val decoder = App.provide(context).requireAuxVideoDecoder()
        val createPicture = { host: Context ->
            AuxPictureView(host, display.displayId, decoder, scaleX, scaleY, onSurfaceReady)
        }
        try {
            if (AppPermissions.isOverlayGranted(context)) {
                showOverlay(context, display, createPicture)
                AppLog.i("AuxDisplayHost: the second screen is up on display ${display.displayId} as an overlay")
            } else if (context is Activity) {
                val shown = AuxDisplayPresentation(context, display, createPicture)
                shown.show()
                presentation = shown
                AppLog.i("AuxDisplayHost: the second screen is up on display ${display.displayId} " +
                    "in the projection activity (no overlay permission)")
            }
        } catch (e: Exception) {
            // Never fatal to the session: the main picture is the one the driver is using.
            AppLog.e("AuxDisplayHost: could not open the second screen: ${e.message}")
            dismiss("it failed to open")
        }
    }

    /** The panel the phone was told about, else whichever one the settings resolve to now. */
    private fun resolveDisplay(context: Context): Display? {
        SecondScreenHub.announcedDisplayId?.let { id -> DisplayTargets.display(context, id)?.let { return it } }
        val settings = App.provide(context).settings
        return DisplayTargets.auxDisplay(context, settings)?.let { DisplayTargets.display(context, it.displayId) }
    }

    private fun showOverlay(context: Context, display: Display, createPicture: (Context) -> AuxPictureView) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val displayContext = context.applicationContext.createDisplayContext(display)
        // A window context of the overlay's own type, where the release has one, so the window
        // takes that display's configuration rather than the built-in panel's.
        val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(type, null)
        } else {
            displayContext
        }
        val manager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply { title = "OpenHeadunit second screen" }
        val view = createPicture(host)
        manager.addView(view, params)
        overlay = view
        overlayManager = manager
    }

    private fun dismiss(reason: String) {
        overlay?.let { view ->
            view.release(reason)
            try { overlayManager?.removeView(view) } catch (_: Exception) {}
            AppLog.i("AuxDisplayHost: the second screen overlay is closed because $reason")
        }
        overlay = null
        overlayManager = null
        presentation?.let { shown -> try { shown.dismiss() } catch (_: Exception) {} }
        presentation = null
    }
}
