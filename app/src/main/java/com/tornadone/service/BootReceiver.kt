package com.tornadone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tornadone.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferencesManager(context)
            if (prefs.onboardingComplete && prefs.autoStartService) {
                GestureService.start(context)
            }
        }
    }
}
