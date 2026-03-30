package com.doorentry.household

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button
    private lateinit var btnManagePins: Button
    private lateinit var cbAutoStart: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus      = findViewById(R.id.tvServiceStatus)
        btnToggle     = findViewById(R.id.btnToggleService)
        btnSettings   = findViewById(R.id.btnSettings)
        btnManagePins = findViewById(R.id.btnManagePins)
        cbAutoStart   = findViewById(R.id.cbAutoStart)

        btnToggle.setOnClickListener { toggleService() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnManagePins.setOnClickListener { startActivity(Intent(this, PinManagementActivity::class.java)) }

        val config = AppConfig(this)
        cbAutoStart.isChecked = config.autoStartOnBoot
        cbAutoStart.setOnCheckedChangeListener { _, checked ->
            config.autoStartOnBoot = checked
        }

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // Check for app updates
        UpdateChecker(this).checkForUpdates()

        findViewById<android.widget.TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == DoorbellService::class.java.name
        }
    }

    private fun updateStatus() {
        if (isServiceRunning()) {
            tvStatus.text = "Service: Running"
            tvStatus.setTextColor(getColor(R.color.status_running))
            btnToggle.text = "Stop Service"
        } else {
            tvStatus.text = "Service: Stopped"
            tvStatus.setTextColor(getColor(R.color.status_stopped))
            btnToggle.text = "Start Service"
        }
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            stopService(Intent(this, DoorbellService::class.java))
        } else {
            startForegroundService(Intent(this, DoorbellService::class.java))
        }
        // Brief delay to let the service state settle before reading it
        btnToggle.postDelayed({ updateStatus() }, 500)
    }

    private fun showSettingsDialog() {
        val config = AppConfig(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        val etHost = view.findViewById<android.widget.EditText>(R.id.etHost)
        val etPort = view.findViewById<android.widget.EditText>(R.id.etPort)
        val etUser = view.findViewById<android.widget.EditText>(R.id.etUser)
        val etPass = view.findViewById<android.widget.EditText>(R.id.etPass)

        etHost.setText(config.mqttHost)
        etPort.setText(config.mqttPort.toString())
        etUser.setText(config.mqttUsername)
        etPass.setText(config.mqttPassword)

        AlertDialog.Builder(this)
            .setTitle("MQTT Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                config.mqttHost     = etHost.text.toString().trim()
                config.mqttPort     = etPort.text.toString().toIntOrNull() ?: 1883
                config.mqttUsername = etUser.text.toString().trim()
                config.mqttPassword = etPass.text.toString()
                // Restart service to apply new settings
                if (isServiceRunning()) {
                    stopService(Intent(this, DoorbellService::class.java))
                    btnToggle.postDelayed({
                        startForegroundService(Intent(this, DoorbellService::class.java))
                        updateStatus()
                    }, 600)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
