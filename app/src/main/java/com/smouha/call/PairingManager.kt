package com.smouha.call

import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified Singleton PairingManager (shared across MainActivity & CallBridgeService).
 *
 * Supports 3 seamless pairing flows:
 * 1) 1-Tap Zero-IP Fast Pair: user taps "⚡ 1-Tap Fast Pair (2 min)" on phone, then clicks
 *    "⚡ Auto-Pair Phone" on PC extension (no IP or QR needed!).
 * 2) Reverse QR: PC shows QR -> phone scans QR -> PC auto-discovers phone & pairs.
 * 3) Phone QR / JSON: phone shows visual QR & JSON -> PC pairs.
 */
class PairingManager(private val prefs: Prefs) {
    data class PairOffer(
        val secret: String,
        val expiresAt: Long
    )

    private val offer = AtomicReference<PairOffer?>(null)
    private val hostPairUntil = AtomicLong(0L)
    private val pendingPcSecret = AtomicReference<String?>(null)
    private val rng = SecureRandom()

    fun refreshOffer(): PairOffer {
        val secret = buildString {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            repeat(10) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
        val o = PairOffer(secret, System.currentTimeMillis() + Prefs.PAIR_SECRET_TTL_MS)
        offer.set(o)
        return o
    }

    fun currentOffer(): PairOffer {
        val o = offer.get()
        if (o == null || o.expiresAt < System.currentTimeMillis()) {
            return refreshOffer()
        }
        return o
    }

    /** Opens 2-minute 1-Tap Zero-IP Fast Pair window. */
    fun enableHostPairWindow(windowMs: Long = 120_000L) {
        currentOffer()
        hostPairUntil.set(System.currentTimeMillis() + windowMs)
        pendingPcSecret.set(null)
    }

    fun hostPairWindowOpen(): Boolean {
        return System.currentTimeMillis() < hostPairUntil.get()
    }

    fun hostPairRemainingMs(): Long {
        return (hostPairUntil.get() - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Stores secret from scanned PC QR so the PC can auto-discover and pair immediately. */
    fun acceptPcOffer(secret: String, expMs: Long = 0L): Boolean {
        val s = secret.trim()
        if (s.length < 6) return false
        pendingPcSecret.set(s)
        val until = if (expMs > System.currentTimeMillis()) expMs else System.currentTimeMillis() + 120_000L
        hostPairUntil.set(until)
        return true
    }

    fun validateAndConsume(secret: String): Boolean {
        val s = secret.trim()
        if (s.isEmpty()) return false

        // 1) Exact match against pending scanned PC secret
        val pending = pendingPcSecret.get()
        if (pending != null && pending.equals(s, ignoreCase = false)) {
            pendingPcSecret.set(null)
            hostPairUntil.set(0L)
            refreshOffer()
            return true
        }

        // 2) 1-Tap Fast Pair window open — accept PC auto-pair or offer secret
        if (System.currentTimeMillis() < hostPairUntil.get()) {
            pendingPcSecret.set(null)
            hostPairUntil.set(0L)
            refreshOffer()
            return true
        }

        // 3) Classic phone-offer secret
        val o = offer.get() ?: return false
        if (o.expiresAt < System.currentTimeMillis()) return false
        if (o.secret != s) return false
        refreshOffer()
        return true
    }

    fun issueSessionToken(): String {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.sessionToken = token
        return token
    }

    fun buildPayloadJson(ip: String?): String {
        val o = currentOffer()
        return JSONObject()
            .put("v", 1)
            .put("app", "SmouhaCall")
            .put("brand", Prefs.BRAND_TAG)
            .put("deviceId", prefs.deviceId)
            .put("deviceName", prefs.deviceName)
            .put("ip", ip ?: "")
            .put("port", Prefs.PORT)
            .put("secret", o.secret)
            .put("exp", o.expiresAt)
            .toString()
    }
}
