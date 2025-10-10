package com.example.myapplication.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.myapplication.R
import com.company.rtkgps.core.GPSFilterManager
import com.company.rtkgps.core.GPSFilterPreferences
import com.company.rtkgps.core.GPSFilterSettings

/**
 * GPS Settings Dialog
 * Allows user to select GPS filter mode and configure distance-based transmission
 */
class GPSSettingsDialog : DialogFragment() {

    private lateinit var filterModeGroup: RadioGroup
    private lateinit var radioRawGPS: RadioButton
    private lateinit var radioGPSKalman: RadioButton
    private lateinit var radioMADKalman: RadioButton
    private lateinit var radioIMUKalman: RadioButton
    private lateinit var radioRTKNTRIP: RadioButton
    private lateinit var distanceIntervalEdit: EditText
    private lateinit var alwaysSendRawCheckbox: CheckBox
    private lateinit var applyButton: Button
    private lateinit var cancelButton: Button

    private lateinit var preferences: GPSFilterPreferences

    private var onSettingsAppliedListener: ((GPSFilterSettings) -> Unit)? = null

    companion object {
        fun newInstance(onApplied: (GPSFilterSettings) -> Unit): GPSSettingsDialog {
            return GPSSettingsDialog().apply {
                onSettingsAppliedListener = onApplied
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        preferences = GPSFilterPreferences(requireContext())

        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_gps_settings, null)

        // Initialize views
        filterModeGroup = view.findViewById(R.id.gps_filter_mode_group)
        radioRawGPS = view.findViewById(R.id.radio_raw_gps)
        radioGPSKalman = view.findViewById(R.id.radio_gps_kalman)
        radioMADKalman = view.findViewById(R.id.radio_mad_kalman)
        radioIMUKalman = view.findViewById(R.id.radio_imu_kalman)
        radioRTKNTRIP = view.findViewById(R.id.radio_rtk_ntrip)
        distanceIntervalEdit = view.findViewById(R.id.distance_interval_edit)
        alwaysSendRawCheckbox = view.findViewById(R.id.always_send_raw_checkbox)
        applyButton = view.findViewById(R.id.apply_button)
        cancelButton = view.findViewById(R.id.cancel_button)

        // Load current settings
        val currentSettings = preferences.loadSettings()
        loadSettingsToUI(currentSettings)

        // Setup button listeners
        applyButton.setOnClickListener {
            applySettings()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    /**
     * Load settings to UI
     */
    private fun loadSettingsToUI(settings: GPSFilterSettings) {
        // Set filter mode radio button
        when (settings.filterMode) {
            GPSFilterManager.FilterMode.RAW_GPS -> radioRawGPS.isChecked = true
            GPSFilterManager.FilterMode.GPS_KALMAN -> radioGPSKalman.isChecked = true
            GPSFilterManager.FilterMode.MAD_KALMAN -> radioMADKalman.isChecked = true
            GPSFilterManager.FilterMode.IMU_KALMAN -> radioIMUKalman.isChecked = true
            GPSFilterManager.FilterMode.RTK_NTRIP -> radioRTKNTRIP.isChecked = true
        }

        // Set distance interval
        distanceIntervalEdit.setText(settings.distanceIntervalMeters.toString())

        // Set always send raw checkbox (always true, disabled)
        alwaysSendRawCheckbox.isChecked = settings.alwaysSendRawGPS
    }

    /**
     * Apply settings
     */
    private fun applySettings() {
        try {
            // Get selected filter mode
            val selectedFilterMode = when (filterModeGroup.checkedRadioButtonId) {
                R.id.radio_raw_gps -> GPSFilterManager.FilterMode.RAW_GPS
                R.id.radio_gps_kalman -> GPSFilterManager.FilterMode.GPS_KALMAN
                R.id.radio_mad_kalman -> GPSFilterManager.FilterMode.MAD_KALMAN
                R.id.radio_imu_kalman -> GPSFilterManager.FilterMode.IMU_KALMAN
                R.id.radio_rtk_ntrip -> GPSFilterManager.FilterMode.RTK_NTRIP
                else -> GPSFilterManager.FilterMode.MAD_KALMAN
            }

            // Get distance interval
            val distanceInterval = distanceIntervalEdit.text.toString().toDoubleOrNull() ?: 5.0

            // Validate distance interval
            if (distanceInterval < 0.1 || distanceInterval > 1000.0) {
                showError("거리 간격은 0.1m ~ 1000m 사이로 설정해주세요")
                return
            }

            // Create settings
            val newSettings = GPSFilterSettings(
                filterMode = selectedFilterMode,
                distanceIntervalMeters = distanceInterval,
                alwaysSendRawGPS = true // Always true
            )

            // Save settings
            preferences.saveSettings(newSettings)

            // Notify listener
            onSettingsAppliedListener?.invoke(newSettings)

            dismiss()

        } catch (e: Exception) {
            showError("설정 적용 실패: ${e.message}")
        }
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("오류")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }
}
