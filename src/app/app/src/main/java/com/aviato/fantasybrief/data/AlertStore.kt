package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONObject

/**
 * Remembers what has already been announced, so the same fact never fires
 * twice. Entries expire because a player who changed teams in September may
 * legitimately change again in November, and a permanent set would silence
 * the second move.
 */
class AlertStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    /** Filters out anything already announced, then records the rest. */
    fun takeUnseen(alerts: List<Alert>): List<Alert> {
        val seen = load()
        val now = System.currentTimeMillis()
        val fresh = alerts.filter { seen[it.key] == null }
        if (fresh.isEmpty()) return emptyList()

        val updated = seen.toMutableMap()
        fresh.forEach { updated[it.key] = now }
        save(updated.filterValues { now - it < EXPIRY_MILLIS })
        return fresh
    }

    fun clear() = prefs.edit().clear().apply()

    fun isMuted(leagueId: Long): Boolean =
        prefs.getBoolean("mute_$leagueId", false)

    fun setMuted(leagueId: Long, muted: Boolean) =
        prefs.edit().putBoolean("mute_$leagueId", muted).apply()

    var pollHours: Int
        get() = prefs.getInt("poll_hours", 6)
        set(v) = prefs.edit().putInt("poll_hours", v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var lastRunMillis: Long
        get() = prefs.getLong("last_run", 0L)
        set(v) = prefs.edit().putLong("last_run", v).apply()

    var lastRunNote: String
        get() = prefs.getString("last_note", "") ?: ""
        set(v) = prefs.edit().putString("last_note", v).apply()

    private fun load(): Map<String, Long> {
        val raw = prefs.getString(KEY_SEEN, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { put(it, obj.optLong(it)) } }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_SEEN, obj.toString()).apply()
    }

    private companion object {
        const val KEY_SEEN = "seen"
        const val EXPIRY_MILLIS = 14L * 24 * 60 * 60 * 1000  // two weeks
    }
}
