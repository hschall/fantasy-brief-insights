package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Observation(
    val percentOwned: Double,
    val projection: Double,
    val injuryStatus: String?,
    val proTeamId: Int
)

data class ObservationRun(
    val takenAtMillis: Long,
    val players: Map<Int, Observation>
) {
    val ageHours: Long
        get() = (System.currentTimeMillis() - takenAtMillis) / 3_600_000
    val ageMinutes: Long
        get() = (System.currentTimeMillis() - takenAtMillis) / 60_000
}

/**
 * Append-only observation history for things ESPN does NOT log as events:
 * ownership, projections, injury status, NFL team.
 *
 * Replaces the single overwritten snapshot. The point is idempotence — a
 * refresh appends a run, and appending cannot move the 24h anchor that
 * diffs are computed against. Refreshing twenty times leaves the system
 * in the same state as refreshing once.
 *
 * Runs are throttled to one per 20 minutes and pruned to 10 days.
 */
class ObservationStore(context: Context) {

    private val dir = context.filesDir

    private fun file(leagueId: Long, season: Int) =
        File(dir, "observations_${leagueId}_$season.json")

    fun record(leagueId: Long, season: Int, players: Map<Int, Observation>): Boolean {
        if (players.isEmpty()) return false
        val runs = loadRuns(leagueId, season).toMutableList()
        val now = System.currentTimeMillis()

        // Throttle: repeated refreshes in quick succession add nothing but
        // noise and file size.
        runs.maxByOrNull { it.takenAtMillis }?.let {
            if (now - it.takenAtMillis < THROTTLE_MILLIS) return false
        }

        runs.add(ObservationRun(now, players))
        val cutoff = now - RETENTION_MILLIS
        save(leagueId, season, (runs.filter { it.takenAtMillis >= cutoff }).takeLast(MAX_RUNS))
        return true
    }

    /**
     * The newest run at or before (now - windowHours). Falls back to the
     * oldest available run when history is shorter than the window — a
     * 6h-old baseline is still useful, it just needs labelling honestly.
     * Returns null only when there is nothing to compare against.
     */
    fun baseline(leagueId: Long, season: Int, windowHours: Long): ObservationRun? {
        val runs = loadRuns(leagueId, season)
        if (runs.size < 2) return null
        val target = System.currentTimeMillis() - windowHours * 3_600_000
        val newest = runs.maxByOrNull { it.takenAtMillis }!!
        return runs.filter { it.takenAtMillis <= target }
            .maxByOrNull { it.takenAtMillis }
            ?: runs.filter { it.takenAtMillis < newest.takenAtMillis }
                .minByOrNull { it.takenAtMillis }
    }

    /** Oldest-first ownership series for one player, for trend display. */
    fun series(leagueId: Long, season: Int, playerId: Int): List<Pair<Long, Observation>> =
        loadRuns(leagueId, season)
            .sortedBy { it.takenAtMillis }
            .mapNotNull { run -> run.players[playerId]?.let { run.takenAtMillis to it } }

    fun runCount(leagueId: Long, season: Int) = loadRuns(leagueId, season).size

    fun clear(leagueId: Long, season: Int) {
        file(leagueId, season).delete()
    }

    // ---- storage ----------------------------------------------------------
    // Compact positional arrays: [owned, proj, proTeamId, injuryStatus].
    // A 400-player league at one run per 20 min for 10 days is large enough
    // that field names would triple the file.

    private fun loadRuns(leagueId: Long, season: Int): List<ObservationRun> {
        val f = file(leagueId, season)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optJSONObject("p") ?: continue
                    val players = mutableMapOf<Int, Observation>()
                    for (key in p.keys()) {
                        val id = key.toIntOrNull() ?: continue
                        val row = p.optJSONArray(key) ?: continue
                        players[id] = Observation(
                            percentOwned = row.optDouble(0, 0.0),
                            projection = row.optDouble(1, 0.0),
                            proTeamId = row.optInt(2, 0),
                            injuryStatus = row.optString(3, "").ifBlank { null }
                        )
                    }
                    add(ObservationRun(o.optLong("t"), players))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(leagueId: Long, season: Int, runs: List<ObservationRun>) {
        val arr = JSONArray()
        runs.sortedBy { it.takenAtMillis }.forEach { run ->
            val p = JSONObject()
            run.players.forEach { (id, obs) ->
                p.put(
                    id.toString(),
                    JSONArray().apply {
                        put(obs.percentOwned); put(obs.projection)
                        put(obs.proTeamId); put(obs.injuryStatus ?: "")
                    }
                )
            }
            arr.put(JSONObject().apply { put("t", run.takenAtMillis); put("p", p) })
        }
        file(leagueId, season).writeText(arr.toString())
    }

    private companion object {
        // Hourly, not every 20 min. The diff is about ownership drift over
        // a day; a 40-minute difference in baseline age decides nothing.
        const val THROTTLE_MILLIS = 60L * 60 * 1000
        const val RETENTION_MILLIS = 3L * 24 * 60 * 60 * 1000
        // Hard cap on runs regardless of age. 72 hourly runs is three days.
        const val MAX_RUNS = 72
    }
}
