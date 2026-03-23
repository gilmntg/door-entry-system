package com.doorentry.household

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts DoorbellService automatically after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
                AppConfig(context).autoStartOnBoot) {
            context.startForegroundService(Intent(context, DoorbellService::class.java))
        }
    }
}
