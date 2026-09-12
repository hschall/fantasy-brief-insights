package com.aviato.fantasybrief.data

import org.json.JSONObject

data class ProGame(
    val homeId: Int,
    val awayId: Int,
    val dateMillis: Long
)

data class ProTeamInfo(
    val id: Int,
    val abbrev: String,
    val byeWeek: Int,
    /** scoringPeriodId -> games that week. Empty or absent means bye. */
    val gamesByWeek: Map<Int, List<ProGame>>
)

/**
 * Lookup for NFL byes and weekly opponents.
 *
 * Byes and opponents are READ, never hardcoded — the season endpoint is the
 * only honest source, and hardcoding them is wrong every single year.
 */
class ProTeamIndex(private val teams: Map<Int, ProTeamInfo>) {

    val size: Int get() = teams.size

    /** API abbrev when we have it, hardcoded map only as fallback. */
    fun abbrev(proTeamId: Int): String =
        teams[proTeamId]?.abbrev?.takeIf { it.isNotBlank() } ?: Enums.proTeam(proTeamId)

    fun byeWeek(proTeamId: Int): Int? = teams[proTeamId]?.byeWeek?.takeIf { it > 0 }

    /** "@BUF", "vs BUF", "BYE", or null when we simply don't know. */
    fun opponent(proTeamId: Int, week: Int): String? {
        val info = teams[proTeamId] ?: return null
        if (info.byeWeek == week) return "BYE"

        val games = info.gamesByWeek[week] ?: return "BYE"
        if (games.isEmpty()) return "BYE"

        val game = games.first()
        return when (proTeamId) {
            game.awayId -> "@" + abbrev(game.homeId)
            game.homeId -> "vs " + abbrev(game.awayId)
            else -> null
        }
    }

    /** Kickoff in epoch millis, or null if unknown or on bye. */
    fun kickoffMillis(proTeamId: Int, week: Int): Long? =
        teams[proTeamId]?.gamesByWeek?.get(week)
            ?.firstOrNull()?.dateMillis?.takeIf { it > 0 }

    /**
     * "Thu 8:15pm", "Sun 1:00pm". Rendered in the DEVICE timezone, which is
     * what matters for "have I still got time to change this".
     */
    fun kickoffLabel(proTeamId: Int, week: Int): String? {
        val millis = kickoffMillis(proTeamId, week) ?: return null
        return java.text.SimpleDateFormat("EEE h:mma", java.util.Locale.US)
            .format(java.util.Date(millis))
            .replace("AM", "am").replace("PM", "pm")
    }

    /**
     * Has this team's game started?
     *
     * Without it, a player yet to play and a player who scored nothing render
     * identically as 0.0 — the same class of bug as season actuals returning
     * 0.0 before any games exist.
     */
    fun hasKickedOff(proTeamId: Int, week: Int): Boolean {
        val games = teams[proTeamId]?.gamesByWeek?.get(week) ?: return false
        val kickoff = games.firstOrNull()?.dateMillis ?: return false
        return kickoff > 0 && System.currentTimeMillis() >= kickoff
    }

    fun isOnBye(proTeamId: Int, week: Int): Boolean =
        opponent(proTeamId, week) == "BYE"

    companion object {
        val EMPTY = ProTeamIndex(emptyMap())

        fun parse(raw: String): ProTeamIndex {
            val root = JSONObject(raw)
            // Season-level response wraps everything under settings.proTeams.
            val list = root.optJSONObject("settings")?.optJSONArray("proTeams")
                ?: root.optJSONArray("proTeams")
                ?: return EMPTY

            val out = mutableMapOf<Int, ProTeamInfo>()
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                val id = t.optInt("id", -1)
                if (id < 0) continue

                val games = mutableMapOf<Int, List<ProGame>>()
                val byWeek = t.optJSONObject("proGamesByScoringPeriod")
                if (byWeek != null) {
                    for (key in byWeek.keys()) {
                        val week = key.toIntOrNull() ?: continue
                        val arr = byWeek.optJSONArray(key) ?: continue
                        val parsed = buildList {
                            for (g in 0 until arr.length()) {
                                val game = arr.optJSONObject(g) ?: continue
                                add(
                                    ProGame(
                                        homeId = game.optInt("homeProTeamId", -1),
                                        awayId = game.optInt("awayProTeamId", -1),
                                        dateMillis = game.optLong("date", 0L)
                                    )
                                )
                            }
                        }
                        if (parsed.isNotEmpty()) games[week] = parsed
                    }
                }

                out[id] = ProTeamInfo(
                    id = id,
                    abbrev = t.optString("abbrev", ""),
                    byeWeek = t.optInt("byeWeek", 0),
                    gamesByWeek = games
                )
            }
            return ProTeamIndex(out)
        }
    }
}
