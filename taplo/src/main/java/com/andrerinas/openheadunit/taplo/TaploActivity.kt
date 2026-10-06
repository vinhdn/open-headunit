package com.andrerinas.openheadunit.taplo

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andrerinas.openheadunit.contract.HeadUnit
import com.andrerinas.openheadunit.contract.TaploLink

/**
 * The taplo screen: the second Android Auto picture, or a note saying why there is none.
 *
 * The picture is decoded by the head unit app straight into this view's surface (see [TaploLink]);
 * this activity only hands the surface over and shows the head unit's state. When no phone is
 * connected it asks for one, which is what the cluster shows most of the time.
 */
class TaploActivity : Activity() {

    private enum class Status { NO_HEAD_UNIT, NOT_CONNECTED, NOT_SELECTED, WAITING, PICTURE }

    private lateinit var picture: TextureView
    private lateinit var statusPanel: View
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView

    private var surface: Surface? = null

    /** What the head unit was last given, so a size callback repeating it does not restart the decoder. */
    private var sent: Triple<Surface, Int, Int>? = null
    private var service: Messenger? = null
    private var bound = false

    private var connected = false
    private var secondScreen = false
    private var stateKnown = false
    private var cropX = 1f
    private var cropY = 1f

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TaploLink.MSG_STATE) onState(msg.data ?: return)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            sent = null
            stateKnown = false
            send(Message.obtain(null, TaploLink.MSG_REGISTER).apply { replyTo = incoming })
            sendSurface()
        }

        // The head unit's process went away; Android binds again by itself when it comes back.
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildViews())
        render()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent().setClassName(HeadUnit.packageName, TaploLink.SERVICE_CLASS)
        bound = try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to bind to the head unit: ${e.message}")
            false
        }
        if (!bound) {
            Log.w(TAG, "The head unit app ${HeadUnit.packageName} is not there to bind to")
            render()
        }
    }

    override fun onStop() {
        if (bound) {
            send(Message.obtain(null, TaploLink.MSG_UNREGISTER).apply { replyTo = incoming })
            unbindService(connection)
            bound = false
        }
        service = null
        super.onStop()
    }

    private fun buildViews(): View {
        picture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    surface = Surface(texture)
                    applyCrop()
                    sendSurface()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    applyCrop()
                    sendSurface()
                }

                // Told before it goes, so the decoder lets go of a surface that is about to die.
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    send(Message.obtain(null, TaploLink.MSG_SURFACE_GONE))
                    surface?.release()
                    surface = null
                    sent = null
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    // The first frame is what proves the picture is coming, not the state alone.
                    if (statusPanel.visibility == View.VISIBLE && connected && secondScreen) {
                        statusPanel.visibility = View.GONE
                    }
                }
            }
        }
        statusTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B6BE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(24), dp(48), dp(24))
            setBackgroundColor(Color.parseColor("#FF101215"))
            addView(statusTitle)
            addView(statusText)
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun onState(state: Bundle) {
        // Null before the first state of this binding: that one is not a change to answer.
        val wasSelected = if (stateKnown) secondScreen else null
        stateKnown = true
        connected = state.getBoolean(TaploLink.KEY_CONNECTED)
        secondScreen = state.getBoolean(TaploLink.KEY_SECOND_SCREEN)
        cropX = state.getFloat(TaploLink.KEY_CROP_X, 1f)
        cropY = state.getFloat(TaploLink.KEY_CROP_Y, 1f)
        applyCrop()
        // The head unit only takes the surface while it is set to this app, so offer it again.
        if (secondScreen && wasSelected == false) sendSurface(force = true)
        render()
    }

    private fun status(): Status = when {
        !bound -> Status.NO_HEAD_UNIT
        !connected -> Status.NOT_CONNECTED
        !secondScreen -> Status.NOT_SELECTED
        statusPanel.visibility == View.GONE -> Status.PICTURE
        else -> Status.WAITING
    }

    private fun render() {
        val (title, text) = when (status()) {
            Status.NO_HEAD_UNIT -> R.string.state_no_head_unit_title to R.string.state_no_head_unit_text
            Status.NOT_CONNECTED -> R.string.state_not_connected_title to R.string.state_not_connected_text
            Status.NOT_SELECTED -> R.string.state_not_selected_title to R.string.state_not_selected_text
            Status.WAITING -> R.string.state_waiting_title to R.string.state_waiting_text
            Status.PICTURE -> return
        }
        statusTitle.setText(title)
        statusText.setText(text)
        statusPanel.visibility = View.VISIBLE
    }

    /** Android Auto draws the panel's size at the top-left of a larger frame; this crops the rest off. */
    private fun applyCrop() {
        picture.setTransform(Matrix().apply { setScale(cropX, cropY, 0f, 0f) })
    }

    private fun sendSurface(force: Boolean = false) {
        val current = surface ?: return
        if (service == null || picture.width <= 0 || picture.height <= 0) return
        val handed = Triple(current, picture.width, picture.height)
        if (!force && handed == sent) return
        sent = handed
        send(Message.obtain(null, TaploLink.MSG_SURFACE).apply {
            data = Bundle().apply {
                putParcelable(TaploLink.KEY_SURFACE, current)
                putInt(TaploLink.KEY_WIDTH, picture.width)
                putInt(TaploLink.KEY_HEIGHT, picture.height)
                putInt(TaploLink.KEY_DENSITY_DPI, resources.displayMetrics.densityDpi)
            }
        })
    }

    private fun send(msg: Message) {
        try {
            service?.send(msg)
        } catch (e: RemoteException) {
            Log.w(TAG, "The head unit went away: ${e.message}")
            service = null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "TaploActivity"
    }
}
