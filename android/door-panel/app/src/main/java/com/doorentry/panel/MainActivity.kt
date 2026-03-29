package com.doorentry.panel

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.doorentry.panel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mqttManager: MqttManager
    private val handler = Handler(Looper.getMainLooper())

    private val enteredCode = StringBuilder()
    private val MAX_CODE_LENGTH = 8
    private val CLEAR_DELAY_MS = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keepScreenOn()
        setupMqtt()
        setupKeypad()
        setupBiometric()
        setupBellButton()
        setupSettingsAccess()

        // Check for app updates
        UpdateChecker(this).checkForUpdates()
    }

    private var isDimmed = false

    private val dimScreen = Runnable {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        window.attributes = lp
        isDimmed = true
    }

    private fun wakeScreen() {
        val wasDimmed = isDimmed
        isDimmed = false
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        handler.removeCallbacks(dimScreen)
        handler.postDelayed(dimScreen, 30_000L)
    }

    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        // Start dimmed — PIR motion will wake it
        handler.postDelayed(dimScreen, 30_000L)
    }

    private fun setupMqtt() {
        mqttManager = MqttManager(this)
        mqttManager.onAccessResult = { granted ->
            runOnUiThread {
                if (granted) showSuccess() else showDenied()
            }
        }
        mqttManager.onMotion = { handler.post { wakeScreen() } }
        mqttManager.connect()
    }

    private fun setupKeypad() {
        val numButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2,
            binding.btn3, binding.btn4, binding.btn5,
            binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )
        numButtons.forEachIndexed { index, button ->
            button.setOnClickListener { appendDigit(index.toString()) }
        }
        binding.btnClear.setOnClickListener { clearCode() }
        binding.btnEnter.setOnClickListener { submitCode() }
    }

    private fun appendDigit(digit: String) {
        if (enteredCode.length >= MAX_CODE_LENGTH) return
        enteredCode.append(digit)
        updateCodeDisplay()
    }

    private fun clearCode() {
        enteredCode.clear()
        updateCodeDisplay()
        showIdle()
    }

    private fun submitCode() {
        if (enteredCode.isEmpty()) return
        val code = enteredCode.toString()
        clearCode()
        mqttManager.publishCode(code)
        showWaiting()
    }

    private fun updateCodeDisplay() {
        binding.tvCodeDisplay.text = "•".repeat(enteredCode.length)
    }

    private fun setupBiometric() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnFingerprint.visibility = View.VISIBLE
            binding.btnFingerprint.setOnClickListener { startBiometricAuth() }
        } else {
            binding.btnFingerprint.visibility = View.GONE
        }
    }

    private fun startBiometricAuth() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                mqttManager.publishFingerprintAuth()
                showWaiting()
            }
            override fun onAuthenticationFailed() { showDenied() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { showIdle() }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Door Access")
            .setSubtitle("Verify your fingerprint to open the door")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun setupBellButton() {
        binding.btnBell.setOnClickListener {
            mqttManager.publishBell()
            startActivity(Intent(this, VideoCallActivity::class.java))
        }
    }

    private fun setupSettingsAccess() {
        binding.tvStatus.setOnLongClickListener {
            showSettingsDialog()
            true
        }
    }

    private fun showSettingsDialog() {
        val config = AppConfig(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }
        val etHost = EditText(this).apply { hint = "MQTT Host"; setText(config.mqttHost) }
        val etPort = EditText(this).apply { hint = "MQTT Port"; setText(config.mqttPort.toString()) }
        val etUser = EditText(this).apply { hint = "Username"; setText(config.mqttUsername) }
        val etPass = EditText(this).apply {
            hint = "Password"
            setText(config.mqttPassword)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etHost)
        layout.addView(etPort)
        layout.addView(etUser)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save & Reconnect") { _, _ ->
                config.mqttHost = etHost.text.toString().trim()
                config.mqttPort = etPort.text.toString().toIntOrNull() ?: 1883
                config.mqttUsername = etUser.text.toString().trim()
                config.mqttPassword = etPass.text.toString()
                mqttManager.disconnect()
                setupMqtt()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIdle() {
        binding.tvStatus.text = "Enter code or use fingerprint"
        binding.tvStatus.setTextColor(getColor(android.R.color.white))
        binding.layoutRoot.setBackgroundColor(getColor(R.color.bg_idle))
    }

    private fun showWaiting() {
        binding.tvStatus.text = "Checking..."
        binding.tvStatus.setTextColor(getColor(android.R.color.white))
        handler.postDelayed({ showIdle() }, CLEAR_DELAY_MS)
    }

    private fun showSuccess() {
        binding.tvStatus.text = "Access Granted ✓"
        binding.tvStatus.setTextColor(getColor(android.R.color.white))
        binding.layoutRoot.setBackgroundColor(getColor(R.color.bg_success))
        handler.postDelayed({ showIdle() }, CLEAR_DELAY_MS)
    }

    private fun showDenied() {
        binding.tvStatus.text = "Access Denied ✗"
        binding.tvStatus.setTextColor(getColor(android.R.color.white))
        binding.layoutRoot.setBackgroundColor(getColor(R.color.bg_denied))
        handler.postDelayed({ showIdle() }, CLEAR_DELAY_MS)
    }

    private fun showBellRung() {
        binding.tvStatus.text = "Bell ringing..."
        handler.postDelayed({ showIdle() }, CLEAR_DELAY_MS)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk mode — back button disabled
    }

    override fun onResume() {
        super.onResume()
        showIdle()
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttManager.disconnect()
    }
}
