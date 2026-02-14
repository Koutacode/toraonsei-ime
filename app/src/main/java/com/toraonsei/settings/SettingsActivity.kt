package com.toraonsei.settings

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
import com.toraonsei.R
import com.toraonsei.dict.DictionaryActivity

class SettingsActivity : AppCompatActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toast("マイク権限を許可しました")
        } else {
            toast("マイク権限がないと音声入力できません")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.openImeSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.openImePickerButton).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.requestPermissionButton).setOnClickListener {
            ensureMicrophonePermission()
        }

        findViewById<Button>(R.id.openDictionaryButton).setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }
    }

    private fun ensureMicrophonePermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            toast("マイク権限は許可済みです")
            return
        }

        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
