package com.andrerinas.openheadunit.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Day or night, chosen by hand, for the app and for Android Auto separately. Nothing here
 * switches on its own: there is no sensor, clock, sunrise or location behind either choice.
 */
class DarkModeFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingAppTheme: Settings.AppTheme? = null
    private var pendingNightMode: Settings.NightMode? = null

    // Pending AA monochrome settings
    private var pendingAaMonochromeEnabled: Boolean? = null
    private var pendingAaDesaturationLevel: Int? = null

    // View mode (needed for GLES dialog)
    private var pendingViewMode: Settings.ViewMode? = null

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dark_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize pending state from current values
        pendingAppTheme = settings.appTheme
        pendingNightMode = settings.nightMode

        pendingAaMonochromeEnabled = settings.aaMonochromeEnabled
        pendingAaDesaturationLevel = settings.aaDesaturationLevel

        pendingViewMode = settings.viewMode

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        // Detect changes BEFORE saving values to SharedPreferences
        val themeChanged = pendingAppTheme != settings.appTheme
        val viewModeChanged = pendingViewMode != settings.viewMode

        pendingNightMode?.let { settings.nightMode = it }

        // Save AA monochrome settings
        pendingAaMonochromeEnabled?.let { settings.aaMonochromeEnabled = it }
        pendingAaDesaturationLevel?.let { settings.aaDesaturationLevel = it }

        // Save view mode if changed (from GLES dialog)
        if (viewModeChanged) {
            pendingViewMode?.let { settings.viewMode = it }
        }

        pendingAppTheme?.let { newTheme ->
            settings.appTheme = newTheme
            // Extreme dark is its own theme now; the old "extreme at night" switch only ever
            // applied to the automatic themes, which are gone.
            settings.useExtremeDarkMode = false
            if (themeChanged) AppThemeManager.applyStaticTheme(settings)
        }

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
        nightModeUpdateIntent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(nightModeUpdateIntent)

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                ToastUtils.showToast(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }

        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()

        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)

        // Signal visual change so all activities (including MainActivity) pick up changes
        if (themeChanged) AppThemeManager.signalVisualChange()
    }

    private fun checkChanges() {
        hasChanges = pendingAppTheme != settings.appTheme ||
                pendingNightMode != settings.nightMode ||
                pendingAaMonochromeEnabled != settings.aaMonochromeEnabled ||
                pendingAaDesaturationLevel != settings.aaDesaturationLevel ||
                pendingViewMode != settings.viewMode

        // View mode change requires restart
        requiresRestart = pendingViewMode != settings.viewMode

        updateSaveButtonState()
    }

    /** A single-choice dialog over [options], labelled from a string array indexed by stored value. */
    private fun <T> showChoice(titleRes: Int, labels: Array<String>, options: List<T>, value: (T) -> Int,
                               selected: T, onPick: (T) -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(titleRes)
            .setSingleChoiceItems(options.map { labels[value(it)] }.toTypedArray(), options.indexOf(selected)) { dialog, which ->
                onPick(options[which])
                checkChanges()
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        // --- App Theme ---
        items.add(SettingItem.CategoryHeader("appTheme", R.string.app_theme))

        val appThemeTitles = resources.getStringArray(R.array.app_theme)
        items.add(SettingItem.SettingEntry(
            stableId = "appTheme",
            nameResId = R.string.app_theme,
            value = appThemeTitles[pendingAppTheme!!.value],
            onClick = { _ ->
                showChoice(R.string.change_app_theme, appThemeTitles, Settings.AppTheme.values().toList(),
                    { it.value }, pendingAppTheme!!) { pendingAppTheme = it }
            }
        ))

        // --- Android Auto Night Mode ---
        items.add(SettingItem.CategoryHeader("aaNightMode", R.string.night_mode))

        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = nightModeTitles[pendingNightMode!!.value],
            onClick = { _ ->
                showChoice(R.string.night_mode, nightModeTitles, Settings.NightMode.values().toList(),
                    { it.value }, pendingNightMode!!) { pendingNightMode = it }
            }
        ))

        // AA Monochrome toggle — hidden when Night Mode is DAY
        if (pendingNightMode != Settings.NightMode.DAY) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "aaMonochrome",
                nameResId = R.string.aa_monochrome,
                descriptionResId = R.string.aa_monochrome_description,
                isChecked = pendingAaMonochromeEnabled!!,
                onCheckedChanged = { isChecked ->
                    if (isChecked && pendingViewMode != Settings.ViewMode.GLES) {
                        // Set to true so the submitted list matches the visual switch state.
                        // This way DiffUtil can detect the revert to false on cancel.
                        pendingAaMonochromeEnabled = true
                        updateSettingsList()
                        // Show GLES required dialog
                        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                            .setTitle(R.string.gles_required_title)
                            .setMessage(R.string.gles_required_message)
                            .setPositiveButton(R.string.enable_gles) { _, _ ->
                                pendingViewMode = Settings.ViewMode.GLES
                                pendingAaMonochromeEnabled = true
                                checkChanges()
                                updateSettingsList()
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .setOnCancelListener {
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .show()
                    } else {
                        pendingAaMonochromeEnabled = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))

            // Desaturation slider — only visible when AA monochrome is ON
            if (pendingAaMonochromeEnabled == true) {
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "aaDesaturation",
                    nameResId = R.string.aa_desaturation,
                    value = "${pendingAaDesaturationLevel}%",
                    sliderValue = pendingAaDesaturationLevel!!.toFloat(),
                    valueFrom = 0f,
                    valueTo = 100f,
                    stepSize = 0f,
                    onValueChanged = { value ->
                        pendingAaDesaturationLevel = value.toInt()
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }
}
