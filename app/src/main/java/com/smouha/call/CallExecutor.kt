package com.smouha.call

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Places a direct call when CALL_PHONE is granted; falls back to dialer UI.
 * Opens WhatsApp chat directly via native whatsapp:// scheme (bypassing browser interstitials).
 */
object CallExecutor {
    fun normalizeEgyptPhone(raw: String): String {
        var s = raw.trim().replace(" ", "").replace("-", "")
        if (s.startsWith("00")) s = "+" + s.substring(2)
        if (s.startsWith("0") && !s.startsWith("00")) {
            s = "+20" + s.substring(1)
        }
        if (!s.startsWith("+") && s.matches(Regex("^1[0125]\\d{8}$"))) {
            s = "+20$s"
        }
        return s
    }

    /** Digits only for WhatsApp deep link (country code without +). */
    fun digitsForWhatsApp(raw: String): String {
        val norm = normalizeEgyptPhone(raw)
        return norm.replace("+", "").replace(Regex("[^0-9]"), "")
    }

    fun placeCall(ctx: Context, phoneRaw: String): Pair<Boolean, String> {
        val phone = normalizeEgyptPhone(phoneRaw)
        if (phone.length < 8) return false to "invalid_phone"

        val canCall = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            if (canCall) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        if (tm != null) {
                            tm.placeCall(Uri.parse("tel:$phone"), null)
                            return true to "called_telecom"
                        }
                    } catch (_: Exception) {
                    }
                }
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                true to "called"
            } else {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                true to "dialer"
            }
        } catch (e: Exception) {
            false to (e.message ?: "call_failed")
        }
    }

    const val OPEN_CHANNEL_ID = "smouha_open_wa"
    private const val OPEN_NOTIF_ID = 8766

    private fun whatsappIntents(digits: String): List<Pair<Intent, String>> {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val list = mutableListOf<Pair<Intent, String>>()
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            list += Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$digits")).apply {
                setPackage(pkg)
                addFlags(flags)
            } to "whatsapp_direct:$pkg"
        }
        list += Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$digits")).apply {
            addFlags(flags)
        } to "whatsapp_scheme"
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            list += Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
                setPackage(pkg)
                addFlags(flags)
            } to "wa.me:$pkg"
        }
        return list
    }

    fun openWhatsApp(ctx: Context, phoneRaw: String): Pair<Boolean, String> {
        val digits = digitsForWhatsApp(phoneRaw)
        if (digits.length < 8) return false to "invalid_phone"

        val appCtx = ctx.applicationContext
        val attempts = whatsappIntents(digits)

        var lastErr = "whatsapp_failed"
        for ((intent, tag) in attempts) {
            try {
                if (intent.resolveActivity(appCtx.packageManager) == null) continue
                appCtx.startActivity(intent)
                if (BridgeState.uiVisible || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return true to tag
                }
                // Also post heads-up tap notification in case Android 10+ background restriction suppressed launch
                notifyOpenWhatsApp(appCtx, attempts)
                return true to tag
            } catch (e: Exception) {
                lastErr = e.message ?: tag
            }
        }

        return notifyOpenWhatsApp(appCtx, attempts)
    }

    private fun notifyOpenWhatsApp(
        appCtx: Context,
        attempts: List<Pair<Intent, String>>
    ): Pair<Boolean, String> {
        val nm = NotificationManagerCompat.from(appCtx)
        if (!nm.areNotificationsEnabled()) return false to "notifications_disabled"

        val chosen = attempts.firstOrNull { (intent, _) ->
            intent.resolveActivity(appCtx.packageManager) != null
        } ?: attempts.last()

        val pi = PendingIntent.getActivity(
            appCtx, 1, chosen.first,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(appCtx, OPEN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_smouha)
            .setContentTitle("Open WhatsApp chat")
            .setContentText("Tap to open the customer chat requested from PC")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return try {
            nm.notify(OPEN_NOTIF_ID, n)
            true to "notification_tap_to_open"
        } catch (e: Exception) {
            false to (e.message ?: "notify_failed")
        }
    }
}
