package com.example.myapplication.ui

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.myapplication.R

class RtkSettingsDialogFragment : DialogFragment() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var urlEditText: EditText
    private lateinit var mountEditText: EditText
    private lateinit var idEditText: EditText
    private lateinit var passwordEditText: EditText

    companion object {
        const val PREFS_NAME = "rtk_settings"
        const val KEY_URL = "rtk_url"
        const val KEY_MOUNT = "rtk_mount"
        const val KEY_ID = "rtk_id"
        const val KEY_PASSWORD = "rtk_password"
        
        // Default values
        const val DEFAULT_URL = "RTS2.ngii.go.kr"
        const val DEFAULT_MOUNT = "VRS-RTCM31"
        const val DEFAULT_ID = "mt_user1"
        const val DEFAULT_PASSWORD = "1234"
        
        fun newInstance(): RtkSettingsDialogFragment {
            return RtkSettingsDialogFragment()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_rtk_settings, null)
        
        initializeViews(view)
        loadSettings()
        
        builder.setView(view)
            .setTitle("RTK 설정")
            .setPositiveButton("저장") { _, _ ->
                saveSettings()
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.cancel()
            }
            .setNeutralButton("기본값 복원") { _, _ ->
                restoreDefaults()
            }
        
        return builder.create()
    }
    
    private fun initializeViews(view: View) {
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        urlEditText = view.findViewById(R.id.et_rtk_url)
        mountEditText = view.findViewById(R.id.et_rtk_mount)
        idEditText = view.findViewById(R.id.et_rtk_id)
        passwordEditText = view.findViewById(R.id.et_rtk_password)
    }
    
    private fun loadSettings() {
        urlEditText.setText(sharedPreferences.getString(KEY_URL, DEFAULT_URL))
        mountEditText.setText(sharedPreferences.getString(KEY_MOUNT, DEFAULT_MOUNT))
        idEditText.setText(sharedPreferences.getString(KEY_ID, DEFAULT_ID))
        passwordEditText.setText(sharedPreferences.getString(KEY_PASSWORD, DEFAULT_PASSWORD))
    }
    
    private fun saveSettings() {
        with(sharedPreferences.edit()) {
            putString(KEY_URL, urlEditText.text.toString())
            putString(KEY_MOUNT, mountEditText.text.toString())
            putString(KEY_ID, idEditText.text.toString())
            putString(KEY_PASSWORD, passwordEditText.text.toString())
            apply()
        }
        Toast.makeText(context, "RTK 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }
    
    private fun restoreDefaults() {
        urlEditText.setText(DEFAULT_URL)
        mountEditText.setText(DEFAULT_MOUNT)
        idEditText.setText(DEFAULT_ID)
        passwordEditText.setText(DEFAULT_PASSWORD)
        saveSettings()
    }
    
    interface RtkSettingsListener {
        fun onRtkSettingsChanged(url: String, mount: String, id: String, password: String)
    }
    
    private var listener: RtkSettingsListener? = null
    
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is RtkSettingsListener) {
            listener = context
        }
    }
    
    override fun onDetach() {
        super.onDetach()
        listener = null
    }
    
    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        listener?.onRtkSettingsChanged(
            sharedPreferences.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL,
            sharedPreferences.getString(KEY_MOUNT, DEFAULT_MOUNT) ?: DEFAULT_MOUNT,
            sharedPreferences.getString(KEY_ID, DEFAULT_ID) ?: DEFAULT_ID,
            sharedPreferences.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        )
    }
}