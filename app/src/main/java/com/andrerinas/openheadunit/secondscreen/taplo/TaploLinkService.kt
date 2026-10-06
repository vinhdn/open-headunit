package com.andrerinas.openheadunit.secondscreen.taplo

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.contract.TaploLink
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The head unit's end of [TaploLink]: the taplo companion app binds here, hands over the surface of
 * its view, and the auxiliary decoder renders into it, across the process boundary.
 *
 * The surface is only taken while the second screen is set to [SecondScreenOutputPolicy.Output.TAPLO_APP],
 * so a companion left installed never steals the picture from another output. Everything here runs
 * on the main thread.
 */
class TaploLinkService : Service() {

    private val clients = mutableSetOf<Messenger>()
    private var surface: Surface? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) = handle(msg)
    })

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            App.provide(this@TaploLinkService).commManager.connectionState.collect { pushState() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        clients.clear()
        release("the taplo app went away")
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        release("the taplo link closed")
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            TaploLink.MSG_REGISTER -> msg.replyTo?.let { client ->
                clients += client
                send(client, state())
            }
            TaploLink.MSG_UNREGISTER -> msg.replyTo?.let { clients -= it }
            TaploLink.MSG_SURFACE -> {
                val data = msg.data ?: return
                data.classLoader = Surface::class.java.classLoader
                @Suppress("DEPRECATION")
                val handed = data.getParcelable<Surface>(TaploLink.KEY_SURFACE) ?: return
                attach(handed, data.getInt(TaploLink.KEY_WIDTH), data.getInt(TaploLink.KEY_HEIGHT),
                    data.getInt(TaploLink.KEY_DENSITY_DPI))
            }
            TaploLink.MSG_SURFACE_GONE -> release("the taplo app's surface is going away")
        }
    }

    private fun attach(handed: Surface, width: Int, height: Int, densityDpi: Int) {
        release("the taplo app handed over a new surface")
        surface = handed
        val settings = App.provide(this).settings
        if (width > 0 && height > 0) {
            val target = SecondScreenOutputPolicy.Target(width, height, if (densityDpi in 1..640) densityDpi else 160)
            if (target != settings.taploAppLastTarget) {
                // The phone was told a size at service discovery and keeps it for the session.
                AppLog.i("TaploLink: the taplo app is ${width}x$height@$densityDpi, announced from the next session")
                settings.taploAppLastTarget = target
            }
        }
        if (!isSelected()) {
            AppLog.i("TaploLink: the taplo app attached, but the second screen is not set to it")
            pushState()
            return
        }
        App.provide(this).requireAuxVideoDecoder().setSurface(handed)
        App.provide(this).commManager.requestAuxKeyframe("the taplo app handed over its surface")
        AppLog.i("TaploLink: the second screen draws into the taplo app (${width}x$height)")
        pushState()
    }

    /** A surface loss, never the session ending: what the decoder learned of the stream has to survive it. */
    private fun release(reason: String) {
        val current = surface ?: return
        surface = null
        AppLog.i("TaploLink: releasing the taplo app's surface because $reason")
        App.provide(this).requireAuxVideoDecoder().stopIfCurrentSurface(current, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        current.release()
    }

    private fun isSelected(): Boolean {
        val settings = App.provide(this).settings
        return settings.auxDisplayEnabled && settings.auxOutput == SecondScreenOutputPolicy.Output.TAPLO_APP
    }

    private fun state(): Bundle {
        val settings = App.provide(this).settings
        val target = settings.taploAppLastTarget
        val (cropX, cropY) = AuxDisplayProfilePolicy.marginCropScale(
            AuxDisplayProfilePolicy.profileFor(target.widthPx, target.heightPx, target.densityDpi)
        )
        return Bundle().apply {
            putBoolean(TaploLink.KEY_CONNECTED,
                App.provide(this@TaploLinkService).commManager.connectionState.value is
                    CommManager.ConnectionState.TransportStarted)
            putBoolean(TaploLink.KEY_SECOND_SCREEN, isSelected())
            putFloat(TaploLink.KEY_CROP_X, cropX)
            putFloat(TaploLink.KEY_CROP_Y, cropY)
        }
    }

    private fun pushState() {
        if (clients.isEmpty()) return
        val state = state()
        clients.toList().forEach { send(it, state) }
    }

    private fun send(client: Messenger, state: Bundle) {
        try {
            client.send(Message.obtain(null, TaploLink.MSG_STATE).apply { data = Bundle(state) })
        } catch (e: RemoteException) {
            clients -= client
        }
    }
}
