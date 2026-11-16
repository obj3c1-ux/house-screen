package com.house.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the screen when the Fire Stick finishes starting up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
