package com.andrerinas.openheadunit.utils

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.MutableLiveData

/** Applies the app theme chosen in Settings. It never changes on its own. */
object AppThemeManager {
    val themeVersion = MutableLiveData<Int>()
    private var versionCounter = 0

    private fun signalThemeChange() {
        versionCounter++
        if (Looper.myLooper() == Looper.getMainLooper()) {
            themeVersion.value = versionCounter
        } else {
            themeVersion.postValue(versionCounter)
        }
    }

    fun signalVisualChange() {
        signalThemeChange()
    }

    fun applyStaticTheme(settings: Settings) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                applyStaticTheme(settings)
            }
            return
        }
        val mode = when (settings.appTheme) {
            Settings.AppTheme.CLEAR -> AppCompatDelegate.MODE_NIGHT_NO
            Settings.AppTheme.DARK, Settings.AppTheme.EXTREME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        signalThemeChange()
    }
}
