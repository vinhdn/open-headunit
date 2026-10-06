package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The auxiliary display's picture: the decoder renders straight into this view's surface.
 *
 * Scaled from its top-left by [cropScaleX]/[cropScaleY], so the blank margin Android Auto leaves
 * at the right and bottom of each frame falls off the panel instead of shrinking it. Hosted either
 * by [AuxDisplayPresentation] or by an overlay window, which is why it owns the surface itself.
 */
@SuppressLint("ViewConstructor")
internal class AuxPictureView(
    context: Context,
    private val displayId: Int,
    private val decoder: VideoDecoder,
    private val cropScaleX: Float,
    private val cropScaleY: Float,
    private val onSurfaceReady: () -> Unit,
) : TextureView(context) {

    private var surface: Surface? = null

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                AppLog.i("AuxPictureView: surface ready on display $displayId " +
                    "(margin crop x$cropScaleX, x$cropScaleY)")
                setTransform(Matrix().apply { setScale(cropScaleX, cropScaleY, 0f, 0f) })
                val created = Surface(texture)
                surface = created
                decoder.setSurface(created)
                // A surface recreated mid-session restarts the decoder, which then needs a keyframe.
                onSurfaceReady()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                release("the auxiliary display went away")
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    /**
     * Only if it is still ours: a decoder that has already moved on must not be stopped by the
     * window that used to own it. Stopped as a surface loss, never as the session ending: the
     * stream goes on, and the codec type and parameter sets learned from it have to survive.
     */
    fun release(reason: String) {
        val current = surface ?: return
        surface = null
        AppLog.i("AuxPictureView: releasing the surface on display $displayId because $reason")
        decoder.stopIfCurrentSurface(current, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        current.release()
    }
}
