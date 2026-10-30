package com.smouha.call

import android.content.Context
import android.os.PowerManager

/**
 * Short CPU wake when a Call/WhatsApp request arrives so the first press works after idle.
 * Released automatically after [ms] — does not hold a continuous lock.
 */
object BriefWake {
    @Volatile private var lock: PowerManager.WakeLock? = null

    fun pulse(ctx: Context, ms: Long = 8000L) {
        try {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmouhaCall:brief")
            wl.setReferenceCounted(false)
            wl.acquire(ms.coerceIn(2000L, 15000L))
            lock = wl
        } catch (_: Exception) {
        }
    }
}
