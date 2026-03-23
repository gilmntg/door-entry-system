package com.doorentry.household

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PinManagementActivity : AppCompatActivity() {

    private lateinit var mqttManager: MqttManager
    private lateinit var tvFeedback: TextView
    private var pinsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_management)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Manage PINs"

        tvFeedback = findViewById(R.id.tvFeedback)

        mqttManager = MqttManager(this)
        mqttManager.onPinValue = { slot, pin ->
            runOnUiThread {
                when (slot) {
                    "admin"     -> findViewById<android.widget.EditText>(R.id.etAdminPin).setText(pin)
                    "resident1" -> findViewById<android.widget.EditText>(R.id.etResident1Pin).setText(pin)
                    "resident2" -> findViewById<android.widget.EditText>(R.id.etResident2Pin).setText(pin)
                }
            }
        }
        mqttManager.connect()

        setupSlot(R.id.etAdminPin,     R.id.btnSaveAdmin,     "admin")
        setupSlot(R.id.etResident1Pin, R.id.btnSaveResident1, "resident1")
        setupSlot(R.id.etResident2Pin, R.id.btnSaveResident2, "resident2")

        val pinFields = listOf(
            findViewById<EditText>(R.id.etAdminPin),
            findViewById<EditText>(R.id.etResident1Pin),
            findViewById<EditText>(R.id.etResident2Pin)
        )
        findViewById<Button>(R.id.btnToggleVisibility).setOnClickListener { btn ->
            pinsVisible = !pinsVisible
            val inputType = if (pinsVisible)
                InputType.TYPE_CLASS_NUMBER
            else
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            pinFields.forEach { et ->
                et.inputType = inputType
                et.setSelection(et.text.length)  // keep cursor at end
            }
            (btn as Button).text = if (pinsVisible) "Hide" else "Show"
        }
    }

    private fun setupSlot(editTextId: Int, buttonId: Int, slot: String) {
        val et = findViewById<EditText>(editTextId)
        val btn = findViewById<Button>(buttonId)
        btn.setOnClickListener {
            val pin = et.text.toString().trim()
            if (pin.length < 4) {
                showFeedback("PIN must be at least 4 digits", error = true)
                return@setOnClickListener
            }
            mqttManager.publishPinUpdate(slot, pin)
            et.text.clear()
            showFeedback("PIN updated for $slot")
        }
    }

    private fun showFeedback(msg: String, error: Boolean = false) {
        tvFeedback.text = msg
        tvFeedback.setTextColor(getColor(if (error) R.color.status_stopped else R.color.status_running))
        tvFeedback.visibility = android.view.View.VISIBLE
        tvFeedback.postDelayed({ tvFeedback.visibility = android.view.View.INVISIBLE }, 3000)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mqttManager.disconnect() } catch (e: Exception) {}
    }
}
