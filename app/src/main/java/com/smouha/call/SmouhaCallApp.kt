package com.smouha.call

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SmouhaCallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CallBridgeService.CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
            val open = NotificationChannel(
                CallExecutor.OPEN_CHANNEL_ID,
                "Open WhatsApp",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to open a WhatsApp chat requested from the PC"
                setShowBadge(false)
            }
            nm.createNotificationChannel(open)
        }
    }
}
