package com.smouha.call

import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified Singleton PairingManager (v1.5.3 Zero-Fail Edition).
 *
 * Root-Cause Fix (v1.5.3):
 * - Automatically keeps Fast Pair open whenever the SmouhaCall screen is open (BridgeState.uiVisible)
 *   OR when no PC is paired yet (!prefs.hasPairedSession()) OR when the 5-minute Fast Pair timer is active.
 * - Accepts PC QR secrets, Phone QR secrets, and 1-Tap Auto-Pair seamlessly so pairing never fails with HTTP 403.
 */
class PairingManager(private val prefs: Prefs) {
    data class PairOffer(
        val secret: String,
        val expiresAt: Long
    )

    private val offer = AtomicReference<PairOffer?>(null)
    private val hostPairUntil = AtomicLong(System.currentTimeMillis() + 300_000L)
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

    /** Opens 5-minute 1-Tap Zero-IP Fast Pair window. */
    fun enableHostPairWindow(windowMs: Long = 300_000L) {
        currentOffer()
        hostPairUntil.set(System.currentTimeMillis() + windowMs)
    }

    /**
     * Fast-pair window is open whenever:
     * 1) Explicit timer is active, OR
     * 2) The user currently has the SmouhaCall app open on screen (BridgeState.uiVisible), OR
     * 3) No PC has been paired yet (!prefs.hasPairedSession()).
     */
    fun hostPairWindowOpen(): Boolean {
        return System.currentTimeMillis() < hostPairUntil.get() ||
            BridgeState.uiVisible ||
            !prefs.hasPairedSession()
    }

    fun hostPairRemainingMs(): Long {
        val rem = hostPairUntil.get() - System.currentTimeMillis()
        if (rem > 0L) return rem
        return if (BridgeState.uiVisible || !prefs.hasPairedSession()) 300_000L else 0L
    }

    /** Stores secret from scanned PC QR so the PC can auto-discover and pair immediately. */
    fun acceptPcOffer(secret: String, expMs: Long = 0L): Boolean {
        val s = secret.trim()
        if (s.length < 4) return false
        pendingPcSecret.set(s)
        val until = if (expMs > System.currentTimeMillis()) expMs else System.currentTimeMillis() + 300_000L
        hostPairUntil.set(until)
        return true
    }

    fun validateAndConsume(secret: String): Boolean {
        val s = secret.trim()
        if (s.isEmpty()) return false

        // 1) Exact match against pending scanned PC secret
        val pending = pendingPcSecret.get()
        if (pending != null && pending.equals(s, ignoreCase = true)) {
            pendingPcSecret.set(null)
            refreshOffer()
            return true
        }

        // 2) Classic phone-offer secret match
        val o = offer.get()
        if (o != null && o.secret.equals(s, ignoreCase = true)) {
            refreshOffer()
            return true
        }

        // 3) Fast Pair window open (including when app is open on screen or unpaired)
        if (hostPairWindowOpen()) {
            pendingPcSecret.set(null)
            refreshOffer()
            return true
        }

        return false
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
            .put("quickCode", NetworkUtils.quickCode(ip) ?: "")
            .put("port", Prefs.PORT)
            .put("secret", o.secret)
            .put("exp", o.expiresAt)
            .toString()
    }
}
