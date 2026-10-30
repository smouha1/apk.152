package com.smouha.call

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException

/**
 * LAN HTTP API for Smouha Papper extension (v1.5.3).
 */
class BridgeHttpServer(
    private val appContext: Context,
    private val prefs: Prefs,
    private val pairing: PairingManager,
    port: Int = Prefs.PORT
) : NanoHTTPD("0.0.0.0", port) {

    @Throws(IOException::class)
    override fun start() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val cors = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization, Accept",
            "Access-Control-Allow-Private-Network" to "true",
            "Cache-Control" to "no-store",
            "Connection" to "keep-alive"
        )

        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also {
                cors.forEach { (k, v) -> it.addHeader(k, v) }
            }
        }

        val path = session.uri.substringBefore('?').trimEnd('/')
        return try {
            when {
                path == "/v1/ping" && session.method == Method.GET -> {
                    if (!authorize(session)) {
                        return json(Response.Status.UNAUTHORIZED, err("unauthorized"), cors)
                    }
                    val counts = ActivityLog.todayCounts(appContext)
                    val ip = NetworkUtils.localIpv4() ?: ""
                    json(
                        Response.Status.OK,
                        JSONObject()
                            .put("ok", true)
                            .put("version", "1.5.3")
                            .put("deviceId", prefs.deviceId)
                            .put("deviceName", prefs.deviceName)
                            .put("brand", Prefs.BRAND_TAG)
                            .put("port", Prefs.PORT)
                            .put("ip", ip)
                            .put("quickCode", NetworkUtils.quickCode(ip) ?: "")
                            .put("callsToday", counts.calls)
                            .put("waToday", counts.whatsapp)
                            .put("ts", System.currentTimeMillis()),
                        cors
                    )
                }
                path == "/v1/pair" && session.method == Method.POST -> {
                    val body = readBody(session)
                    val secret = body.optString("secret", "")
                    if (!pairing.validateAndConsume(secret)) {
                        return json(
                            Response.Status.FORBIDDEN,
                            err("invalid_or_expired_secret"),
                            cors
                        )
                    }
                    val token = pairing.issueSessionToken()
                    val name = body.optString("deviceName", "").ifBlank { null }
                    if (name != null) prefs.deviceName = name
                    ActivityLog.add(appContext, "pair", prefs.deviceName, true, "Paired with PC extension")
                    BridgeState.notifyUi()
                    val ip = NetworkUtils.localIpv4() ?: ""
                    json(
                        Response.Status.OK,
                        JSONObject()
                            .put("ok", true)
                            .put("token", token)
                            .put("deviceId", prefs.deviceId)
                            .put("deviceName", prefs.deviceName)
                            .put("brand", Prefs.BRAND_TAG)
                            .put("port", Prefs.PORT)
                            .put("ip", ip)
                            .put("quickCode", NetworkUtils.quickCode(ip) ?: ""),
                        cors
                    )
                }
                path == "/v1/call" && session.method == Method.POST -> {
                    if (!authorize(session)) {
                        return json(Response.Status.UNAUTHORIZED, err("unauthorized"), cors)
                    }
                    val body = readBody(session)
                    val phone = body.optString("phone", "").ifBlank {
                        body.optString("number", "")
                    }
                    BriefWake.pulse(appContext)
                    val (ok, detail) = CallExecutor.placeCall(appContext, phone)
                    val norm = CallExecutor.normalizeEgyptPhone(phone)
                    ActivityLog.add(appContext, "call", norm, ok, detail)
                    BridgeState.notifyUi()
                    if (ok) {
                        json(
                            Response.Status.OK,
                            JSONObject()
                                .put("ok", true)
                                .put("started", true)
                                .put("detail", detail)
                                .put("phone", norm),
                            cors
                        )
                    } else {
                        json(Response.Status.BAD_REQUEST, err(detail), cors)
                    }
                }
                path == "/v1/whatsapp" && session.method == Method.POST -> {
                    if (!authorize(session)) {
                        return json(Response.Status.UNAUTHORIZED, err("unauthorized"), cors)
                    }
                    BriefWake.pulse(appContext)
                    val body = readBody(session)
                    val phone = body.optString("phone", "").ifBlank {
                        body.optString("number", "")
                    }
                    val (ok, detail) = CallExecutor.openWhatsApp(appContext, phone)
                    val norm = CallExecutor.normalizeEgyptPhone(phone)
                    ActivityLog.add(appContext, "whatsapp", norm, ok, detail)
                    BridgeState.notifyUi()
                    if (ok) {
                        json(
                            Response.Status.OK,
                            JSONObject()
                                .put("ok", true)
                                .put("started", true)
                                .put("detail", detail)
                                .put("phone", norm)
                                .put("wa", CallExecutor.digitsForWhatsApp(phone)),
                            cors
                        )
                    } else {
                        json(Response.Status.BAD_REQUEST, err(detail), cors)
                    }
                }
                path == "/v1/info" && session.method == Method.GET -> {
                    val windowOpen = pairing.hostPairWindowOpen()
                    val remSec = (pairing.hostPairRemainingMs() / 1000L).toInt()
                    val counts = ActivityLog.todayCounts(appContext)
                    val ip = NetworkUtils.localIpv4() ?: ""
                    val offer = pairing.currentOffer()
                    val infoObj = JSONObject()
                        .put("ok", true)
                        .put("app", "SmouhaCall")
                        .put("version", "1.5.3")
                        .put("deviceId", prefs.deviceId)
                        .put("deviceName", prefs.deviceName)
                        .put("brand", Prefs.BRAND_TAG)
                        .put("paired", prefs.hasPairedSession())
                        .put("sessions", prefs.sessionTokens().size)
                        .put("pairWindowOpen", windowOpen)
                        .put("pairRemainingSec", remSec)
                        .put("pairOfferSecret", offer.secret)
                        .put("callsToday", counts.calls)
                        .put("waToday", counts.whatsapp)
                        .put("port", Prefs.PORT)
                        .put("ip", ip)
                        .put("quickCode", NetworkUtils.quickCode(ip) ?: "")
                        .put("ts", System.currentTimeMillis())
                    json(Response.Status.OK, infoObj, cors)
                }
                path == "/v1/activity" && session.method == Method.GET -> {
                    if (!authorize(session)) {
                        return json(Response.Status.UNAUTHORIZED, err("unauthorized"), cors)
                    }
                    val entries = ActivityLog.list(appContext)
                    val arr = org.json.JSONArray()
                    for (e in entries.take(20)) {
                        arr.put(
                            JSONObject()
                                .put("ts", e.ts)
                                .put("kind", e.kind)
                                .put("phone", e.phone)
                                .put("ok", e.ok)
                                .put("detail", e.detail)
                        )
                    }
                    json(
                        Response.Status.OK,
                        JSONObject().put("ok", true).put("entries", arr),
                        cors
                    )
                }
                else -> json(Response.Status.NOT_FOUND, err("not_found"), cors)
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, err(e.message ?: "error"), cors)
        }
    }

    private fun authorize(session: IHTTPSession): Boolean {
        val h = session.headers["authorization"]
            ?: session.headers["Authorization"]
            ?: return false
        val bearer = h.removePrefix("Bearer").trim()
        if (bearer.isEmpty()) return false
        return prefs.isValidSessionToken(bearer)
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: ""
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun json(status: Response.Status, obj: JSONObject, cors: Map<String, String>): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        cors.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }
}
