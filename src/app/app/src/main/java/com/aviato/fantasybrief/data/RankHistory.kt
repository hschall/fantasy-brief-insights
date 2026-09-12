package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Analyst rank history, so the app can say a player MOVED.
 *
 * Ownership delta was the founding signal of this project — what the wider
 * fantasy world is reacting to before your leaguemates notice. A rank delta
 * is the same idea one step upstream: eight analysts revising a number is
 * the reaction, and ownership follows it.
 *
 * Same shape as ObservationStore and the same reasoning about size: a
 * per-week file, capped, pruned on write.
 *
 * CAVEAT worth remembering. The consensus is a MEDIAN over however many
 * sources published, and that count varies. A player can appear to move
 * because one source dropped out rather than because anyone changed their
 * mind. Deltas of one place are therefore weak evidence; two or more is
 * where it starts to mean something.
 */
class RankHistory(context: Context) {

    private val dir = File(context.filesDir, "ranks").apply { mkdirs() }

    private fun file(season: Int, week: Int) = File(dir, "ranks_${season}_$week.json")

    /** playerId -> consensus, as of the last recorded run. */
    fun previous(season: Int, week: Int): Map<Int, Int> {
        val f = file(season, week)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(f.readText())
            // The newest entry is what we compare against; the one before it
            // is what we would compare a second refresh against.
            val runs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val prior = if (runs.size >= 2) runs[runs.size - 2] else runs.lastOrNull()
            val map = prior?.optJSONObject("r") ?: return emptyMap()
            buildMap {
                map.keys().forEach { k ->
                    k.toIntOrNull()?.let { put(it, map.optInt(k)) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun record(season: Int, week: Int, ranks: Map<Int, PlayerRanking>) {
        if (ranks.isEmpty()) return
        val f = file(season, week)
        val now = System.currentTimeMillis()
        val runs = runCatching {
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        }.getOrDefault(JSONArray())

        // Twice a day is plenty: analysts revise on news, not on the minute.
        val last = if (runs.length() > 0) runs.optJSONObject(runs.length() - 1) else null
        if (last != null && now - last.optLong("d") < THROTTLE) return

        val map = JSONObject()
        ranks.forEach { (id, r) -> map.put(id.toString(), r.consensus) }
        runs.put(JSONObject().apply { put("d", now); put("r", map) })

        // Keep the last few runs only; the delta needs two.
        val trimmed = JSONArray()
        val start = maxOf(0, runs.length() - MAX_RUNS)
        for (i in start until runs.length()) trimmed.put(runs.optJSONObject(i))
        f.writeText(trimmed.toString())

        // A week's file is dead once the week is over.
        dir.listFiles()?.forEach { old ->
            if (old != f && now - old.lastModified() > RETENTION) old.delete()
        }
    }

    private companion object {
        const val THROTTLE = 12L * 60 * 60 * 1000
        const val MAX_RUNS = 6
        const val RETENTION = 21L * 24 * 60 * 60 * 1000
    }
}
