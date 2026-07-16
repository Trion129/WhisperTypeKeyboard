package com.whispertype.keyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whispertype.keyboard.R

class SetupActivity : AppCompatActivity() {
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "Microphone allowed" else "Microphone denied",
            Toast.LENGTH_SHORT
        ).show()
        updateMicButton()
    }

    private lateinit var btnMic: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_select_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnMic = findViewById(R.id.btn_mic)
        btnMic.setOnClickListener {
            if (hasMicPermission()) {
                Toast.makeText(this, "Microphone already granted", Toast.LENGTH_SHORT).show()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateMicButton()
    }

    override fun onResume() {
        super.onResume()
        updateMicButton()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updateMicButton() {
        btnMic.text = if (hasMicPermission()) {
            "Microphone granted ✓"
        } else {
            getString(R.string.btn_grant_mic)
        }
    }
}
