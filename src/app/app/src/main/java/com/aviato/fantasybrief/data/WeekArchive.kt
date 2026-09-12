package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One player as he stood in a completed week.
 *
 * Names are stored, not looked up. A player dropped from every roster in the
 * league loses his entry everywhere else — which is exactly why the current
 * past-week view shows dashes for him. Storing the name and the points at the
 * time is the whole point of this file.
 */
data class WeekPlayer(
    val playerId: Int,
    val name: String,
    val position: String,
    val proTeamId: Int,
    val lineupSlotId: Int,
    val projection: Double,
    val actual: Double
) {
    val isStarter: Boolean get() = Enums.isStarterSlot(lineupSlotId)
}

data class WeekRoster(
    val teamId: Int,
    val teamName: String,
    val total: Double,
    val players: List<WeekPlayer>
)

data class WeekArchive(
    val week: Int,
    val takenAtMillis: Long,
    val rosters: Map<Int, WeekRoster>
)

/**
 * Per-week lineup archive.
 *
 * Written once a week is complete and never rewritten — a lineup after the
 * fact is not the lineup that played. The guard is the week number: only a
 * week strictly before the current one is archived, and only if it is not
 * already stored.
 */
class WeekArchiveStore(context: Context) {

    private val dir = File(context.filesDir, "weeks").apply { mkdirs() }

    private fun file(leagueId: Long, season: Int, week: Int) =
        File(dir, "w_${leagueId}_${season}_$week.json")

    fun has(leagueId: Long, season: Int, week: Int) =
        file(leagueId, season, week).exists()

    fun archivedWeeks(leagueId: Long, season: Int): List<Int> =
        dir.listFiles()
            ?.mapNotNull { f ->
                Regex("w_${leagueId}_${season}_(\\d+)\\.json")
                    .find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            }?.sorted() ?: emptyList()

    fun load(leagueId: Long, season: Int, week: Int): WeekArchive? {
        val f = file(leagueId, season, week)
        if (!f.exists()) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val rosters = mutableMapOf<Int, WeekRoster>()
            val arr = root.optJSONArray("r") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val players = mutableListOf<WeekPlayer>()
                val pl = t.optJSONArray("p") ?: JSONArray()
                for (j in 0 until pl.length()) {
                    val o = pl.optJSONObject(j) ?: continue
                    players.add(
                        WeekPlayer(
                            playerId = o.optInt("i"),
                            name = o.optString("n"),
                            position = o.optString("po"),
                            proTeamId = o.optInt("t"),
                            lineupSlotId = o.optInt("s"),
                            projection = o.optDouble("pr", 0.0),
                            actual = o.optDouble("a", 0.0)
                        )
                    )
                }
                val id = t.optInt("id")
                rosters[id] = WeekRoster(id, t.optString("tn"), t.optDouble("tp", 0.0), players)
            }
            WeekArchive(root.optInt("w"), root.optLong("t"), rosters)
        }.getOrNull()
    }

    /**
     * Archives a completed week. No-op if already stored or not yet complete.
     *
     * @param week the week to archive
     * @param currentWeek the live scoring period
     */
    fun archive(
        leagueId: Long,
        season: Int,
        week: Int,
        currentWeek: Int,
        league: League,
        schedule: List<Matchup>
    ): Boolean {
        if (week >= currentWeek) return false          // not finished
        if (has(leagueId, season, week)) return false  // never rewrite

        val totals = mutableMapOf<Int, Double>()
        schedule.filter { it.matchupPeriodId == week }.forEach { m ->
            totals[m.homeTeamId] = m.homePoints
            totals[m.awayTeamId] = m.awayPoints
        }
        // Nothing scored means the week never actually happened.
        if (totals.values.none { it > 0.0 }) return false

        val arr = JSONArray()
        league.teams.forEach { team ->
            val players = JSONArray()
            team.roster.forEach { p ->
                players.put(
                    JSONObject().apply {
                        put("i", p.playerId); put("n", p.name)
                        put("po", p.position); put("t", p.proTeamId)
                        put("s", p.lineupSlotId)
                        put("pr", p.projection ?: 0.0)
                        put("a", p.weeklyActuals[week] ?: 0.0)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", team.id); put("tn", team.name)
                    put("tp", totals[team.id] ?: 0.0); put("p", players)
                }
            )
        }

        file(leagueId, season, week).writeText(
            JSONObject().apply {
                put("w", week); put("t", System.currentTimeMillis()); put("r", arr)
            }.toString()
        )
        return true
    }
}
