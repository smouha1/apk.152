package com.smouha.call

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Rolling local log of call / WhatsApp / pair actions (last [MAX] entries)
 * + live today counters for the Glassy V4 KPI row.
 */
object ActivityLog {
    private const val PREF = "smouha_activity"
    private const val KEY = "entries"
    private const val MAX = 40

    data class Entry(
        val ts: Long,
        val kind: String, // call | whatsapp | pair
        val phone: String,
        val ok: Boolean,
        val detail: String
    )

    data class TodayCounts(
        val calls: Int,
        val whatsapp: Int,
        val total: Int
    )

    fun add(ctx: Context, kind: String, phone: String, ok: Boolean, detail: String) {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val obj = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("kind", kind)
            .put("phone", phone)
            .put("ok", ok)
            .put("detail", detail)
        val next = JSONArray()
        next.put(obj)
        for (i in 0 until minOf(arr.length(), MAX - 1)) {
            next.put(arr.get(i))
        }
        sp.edit().putString(KEY, next.toString()).apply()
    }

    fun list(ctx: Context): List<Entry> {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    ts = o.optLong("ts"),
                    kind = o.optString("kind"),
                    phone = o.optString("phone"),
                    ok = o.optBoolean("ok"),
                    detail = o.optString("detail")
                )
            )
        }
        return out
    }

    fun todayCounts(ctx: Context): TodayCounts {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        var calls = 0
        var wa = 0
        for (e in list(ctx)) {
            if (e.ts < startOfDay || !e.ok) continue
            when (e.kind) {
                "call" -> calls++
                "whatsapp" -> wa++
            }
        }
        return TodayCounts(calls = calls, whatsapp = wa, total = calls + wa)
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
