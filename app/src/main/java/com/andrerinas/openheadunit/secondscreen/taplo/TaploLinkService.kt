package com.andrerinas.openheadunit.secondscreen.taplo

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.NavigationFeed
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

    // What the clients are told depends on these, so a change in Settings reaches them at once.
    private val watchedKeys = setOf("aux-display-enabled", "aux-output", "aux-inset-top-percent",
        "aux-inset-bottom-percent", "taplo-card-text-percent")
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in watchedKeys) pushState()
    }
    private var settingsPrefs: SharedPreferences? = null

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) = handle(msg)
    })

    override fun onCreate() {
        super.onCreate()
        settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(settingsListener)
        }
        scope.launch {
            App.provide(this@TaploLinkService).commManager.connectionState.collect { pushState() }
        }
        scope.launch {
            NavigationFeed.current.collect { pushNav() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        clients.clear()
        release("the taplo app went away")
        return false
    }

    override fun onDestroy() {
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        scope.cancel()
        release("the taplo link closed")
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            TaploLink.MSG_REGISTER -> msg.replyTo?.let { client ->
                clients += client
                send(client, state())
                send(client, nav(), TaploLink.MSG_NAV)
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
            AuxDisplayProfilePolicy.profileFor(target.widthPx, target.heightPx, target.densityDpi, squeezeWide = true)
        )
        val session = when (App.provide(this).commManager.connectionState.value) {
            is CommManager.ConnectionState.TransportStarted -> TaploLink.SESSION_CONNECTED
            is CommManager.ConnectionState.Disconnected, is CommManager.ConnectionState.Error -> TaploLink.SESSION_DISCONNECTED
            else -> TaploLink.SESSION_CONNECTING
        }
        return Bundle().apply {
            putBoolean(TaploLink.KEY_CONNECTED, session == TaploLink.SESSION_CONNECTED)
            putString(TaploLink.KEY_SESSION, session)
            putInt(TaploLink.KEY_CARD_TEXT_PERCENT, settings.taploCardTextPercent)
            putBoolean(TaploLink.KEY_SECOND_SCREEN, isSelected())
            putFloat(TaploLink.KEY_CROP_X, cropX)
            putFloat(TaploLink.KEY_CROP_Y, cropY)
            putInt(TaploLink.KEY_INSET_TOP_PERCENT, settings.auxInsetTopPercent)
            putInt(TaploLink.KEY_INSET_BOTTOM_PERCENT, settings.auxInsetBottomPercent)
        }
    }

    private fun nav(): Bundle {
        val guidance = NavigationFeed.current.value
        return Bundle().apply {
            putBoolean(TaploLink.KEY_NAV_ACTIVE, guidance != null)
            guidance ?: return@apply
            putString(TaploLink.KEY_NAV_ROAD, guidance.road)
            putString(TaploLink.KEY_NAV_ACTION, guidance.action)
            putInt(TaploLink.KEY_NAV_EVENT, guidance.event)
            guidance.side?.let { putInt(TaploLink.KEY_NAV_SIDE, it) }
            guidance.roundaboutExit?.let { putInt(TaploLink.KEY_NAV_ROUNDABOUT_EXIT, it) }
            guidance.distanceMeters?.let { putInt(TaploLink.KEY_NAV_DISTANCE_M, it) }
            guidance.timeSeconds?.let { putInt(TaploLink.KEY_NAV_TIME_S, it) }
            guidance.totalDistanceMeters?.let { putInt(TaploLink.KEY_NAV_TOTAL_DISTANCE_M, it) }
            guidance.totalTimeSeconds?.let { putLong(TaploLink.KEY_NAV_TOTAL_TIME_S, it) }
            guidance.estimatedArrival?.let { putString(TaploLink.KEY_NAV_ETA, it) }
        }
    }

    private fun pushNav() {
        if (clients.isEmpty()) return
        val nav = nav()
        clients.toList().forEach { send(it, nav, TaploLink.MSG_NAV) }
    }

    private fun pushState() {
        if (clients.isEmpty()) return
        val state = state()
        clients.toList().forEach { send(it, state) }
    }

    private fun send(client: Messenger, payload: Bundle, what: Int = TaploLink.MSG_STATE) {
        try {
            client.send(Message.obtain(null, what).apply { data = Bundle(payload) })
        } catch (e: RemoteException) {
            clients -= client
        }
    }
}
