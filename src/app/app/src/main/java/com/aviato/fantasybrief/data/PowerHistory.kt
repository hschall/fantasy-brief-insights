package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only power readings, so the standings table can show movement.
 *
 * Separate from ObservationStore because that one is per-player and this is
 * per-team; migrating its format for one extra map would be worse than a
 * second small file. Same idempotence rule: recording is throttled, and the
 * comparison is against a fixed window rather than "the last run".
 */
class PowerHistory(context: Context) {

    private val dir = context.filesDir

    private fun file(leagueId: Long, season: Int) =
        File(dir, "power_${leagueId}_$season.json")

    fun record(leagueId: Long, season: Int, power: Map<Int, Double>) {
        if (power.isEmpty()) return
        val runs = load(leagueId, season).toMutableList()
        val now = System.currentTimeMillis()
        runs.lastOrNull()?.let { if (now - it.first < THROTTLE) return }
        runs.add(now to power)
        val cutoff = now - RETENTION
        save(leagueId, season, runs.filter { it.first >= cutoff })
    }

    /** The newest reading at or before the window. Null when there is none. */
    fun baseline(leagueId: Long, season: Int, windowHours: Long): Map<Int, Double>? {
        val runs = load(leagueId, season)
        if (runs.size < 2) return null
        val target = System.currentTimeMillis() - windowHours * 3_600_000
        return runs.filter { it.first <= target }.maxByOrNull { it.first }?.second
            ?: runs.dropLast(1).minByOrNull { it.first }?.second
    }

    private fun load(leagueId: Long, season: Int): List<Pair<Long, Map<Int, Double>>> {
        val f = file(leagueId, season)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optJSONObject("p") ?: continue
                    val map = mutableMapOf<Int, Double>()
                    p.keys().forEach { k -> k.toIntOrNull()?.let { map[it] = p.optDouble(k) } }
                    add(o.optLong("t") to map)
                }
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    private fun save(leagueId: Long, season: Int, runs: List<Pair<Long, Map<Int, Double>>>) {
        val arr = JSONArray()
        runs.forEach { (t, map) ->
            val p = JSONObject()
            map.forEach { (id, v) -> p.put(id.toString(), v) }
            arr.put(JSONObject().apply { put("t", t); put("p", p) })
        }
        file(leagueId, season).writeText(arr.toString())
    }

    private companion object {
        const val THROTTLE = 20L * 60 * 1000
        const val RETENTION = 30L * 24 * 60 * 60 * 1000
    }
}
