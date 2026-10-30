package com.smouha.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class CallBridgeService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val pairing = BridgeState.getPairing(this)
        pairing.currentOffer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BridgeState.stopServer()
                releaseWifiLock()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                promoteForegroundSafely(buildNotification())
                val ok = BridgeState.ensureServerRunning(applicationContext)
                if (ok) {
                    acquireWifiLock()
                }
            }
        }
        return START_STICKY
    }

    private fun promoteForegroundSafely(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            try {
                startForeground(NOTIF_ID, notif)
            } catch (_: Exception) {
            }
        }
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "SmouhaCall:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = NetworkUtils.localIpv4() ?: "—"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, Prefs.PORT) + " · $ip")
            .setSmallIcon(R.drawable.ic_stat_smouha)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "smouha_call_bridge"
        const val NOTIF_ID = 8765
        const val ACTION_STOP = "com.smouha.call.STOP"
        const val ACTION_START = "com.smouha.call.START"
    }
}

object BridgeState {
    @Volatile var running: Boolean = false
    @Volatile var uiVisible: Boolean = false
    @Volatile var uiListener: (() -> Unit)? = null

    @Volatile private var prefsInstance: Prefs? = null
    @Volatile private var pairingInstance: PairingManager? = null
    @Volatile private var httpServer: BridgeHttpServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getPrefs(ctx: Context): Prefs {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: Prefs(ctx.applicationContext).also { prefsInstance = it }
        }
    }

    fun getPairing(ctx: Context): PairingManager {
        return pairingInstance ?: synchronized(this) {
            pairingInstance ?: PairingManager(getPrefs(ctx)).also { pairingInstance = it }
        }
    }

    /**
     * Guarantees BridgeHttpServer is listening on 0.0.0.0:8765 even if Android delays or restricts
     * ForegroundService promotion.
     */
    @Synchronized
    fun ensureServerRunning(ctx: Context): Boolean {
        val appCtx = ctx.applicationContext
        val existing = httpServer
        if (existing != null && existing.isAlive) {
            running = true
            notifyUi()
            return true
        }
        try {
            existing?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        return try {
            val s = BridgeHttpServer(appCtx, getPrefs(appCtx), getPairing(appCtx), Prefs.PORT)
            s.start()
            httpServer = s
            running = true
            notifyUi()
            true
        } catch (_: Exception) {
            try {
                Thread.sleep(180)
                val s2 = BridgeHttpServer(appCtx, getPrefs(appCtx), getPairing(appCtx), Prefs.PORT)
                s2.start()
                httpServer = s2
                running = true
                notifyUi()
                true
            } catch (_: Exception) {
                running = false
                notifyUi()
                false
            }
        }
    }

    @Synchronized
    fun stopServer() {
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpServer = null
        running = false
        notifyUi()
    }

    fun notifyUi() {
        val listener = uiListener ?: return
        mainHandler.post {
            try {
                listener.invoke()
            } catch (_: Exception) {
            }
        }
    }
}
