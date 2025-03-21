package org.traccar.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class ServiceRestartReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "org.traccar.client.RESTART_SERVICE") {
            val serviceIntent = Intent(context, Flic2Service::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}