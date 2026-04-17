package com.toraonsei.lock

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toraonsei.R
import com.toraonsei.settings.AppConfigRepository
import com.toraonsei.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PasscodeLockActivity : AppCompatActivity() {

    private lateinit var configRepository: AppConfigRepository
    private lateinit var passcodeInput: EditText
    private lateinit var errorText: TextView
    private lateinit var unlockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepository = AppConfigRepository(applicationContext)

        lifecycleScope.launch {
            val config = configRepository.configFlow.first()
            if (config.unlocked) {
                goToSettings()
                return@launch
            }
            showLockUi()
        }
    }

    private fun showLockUi() {
        setContentView(R.layout.activity_passcode_lock)
        passcodeInput = findViewById(R.id.passcodeInput)
        errorText = findViewById(R.id.passcodeError)
        unlockButton = findViewById(R.id.passcodeUnlockButton)

        unlockButton.setOnClickListener { attemptUnlock() }
        passcodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                attemptUnlock()
                true
            } else {
                false
            }
        }
    }

    private fun attemptUnlock() {
        val entered = passcodeInput.text?.toString().orEmpty()
        lifecycleScope.launch {
            val ok = configRepository.unlockWithPasscode(entered)
            if (ok) {
                errorText.visibility = TextView.GONE
                goToSettings()
            } else {
                errorText.visibility = TextView.VISIBLE
                passcodeInput.setText("")
                passcodeInput.requestFocus()
            }
        }
    }

    private fun goToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}
