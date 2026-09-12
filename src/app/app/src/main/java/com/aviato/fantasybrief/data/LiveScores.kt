package com.aviato.fantasybrief.data

import org.json.JSONObject

data class LiveTeamScore(
    val teamId: Int,
    val total: Double,
    /** playerId to points scored so far this week. */
    val byPlayer: Map<Int, Double>,
    /** playerId to lineupSlotId, so a manager's mid-week lineup change
     *  shows without a full refresh. mMatchup carries both. */
    val slots: Map<Int, Int>,
    /** ESPN's own live score. */
    val livePoints: Double = 0.0,
    /** Live projected FINAL: actual so far plus a pro-rated remainder. */
    val projectedLive: Double = 0.0,
    /** Pregame projection, fixed all week. */
    val projectedPregame: Double = 0.0,
    /** ESPN's own win probability, 0..1. */
    val winProbability: Double? = null
)

data class LiveScoreboard(
    val fetchedAtMillis: Long,
    val scoringPeriod: Int,
    val byTeam: Map<Int, LiveTeamScore>,
    val games: Map<Int, GameState> = emptyMap()
) {
    /** True once anyone has scored — i.e. games are actually underway. */
    /**
     * Any player anywhere with a recorded score — INCLUDING zero. Asking
     * whether anyone had scored yet meant a shut-out or a slow start read
     * as though no game had begun.
     */
    val hasStarted: Boolean
        get() = byTeam.values.any { it.total > 0.0 || it.byPlayer.isNotEmpty() }

    val ageSeconds: Long
        get() = (System.currentTimeMillis() - fetchedAtMillis) / 1000

    fun total(teamId: Int): Double? = byTeam[teamId]?.total

    /** ESPN's live projected final — climbs as players beat their forecast. */
    fun projectedLive(teamId: Int): Double? =
        byTeam[teamId]?.projectedLive?.takeIf { it > 0.0 }

    fun winProbability(teamId: Int): Double? = byTeam[teamId]?.winProbability
    fun points(teamId: Int, playerId: Int): Double? = byTeam[teamId]?.byPlayer?.get(playerId)

    fun slot(teamId: Int, playerId: Int): Int? = byTeam[teamId]?.slots?.get(playerId)

    fun game(proTeamId: Int): GameState? = games[proTeamId]
}

/**
 * Live scoring, fetched on its own.
 *
 * A full refresh makes six requests — league, wire, market, depth charts,
 * activity, matchups — and rewrites the observation history. During a Sunday
 * none of that changes; only the points do. So this is a single call that
 * touches no storage and can be pressed as often as you like.
 *
 * Uses mMatchup because its shape is verified. mMatchupScore may be lighter
 * but has never been probed, and guessing at a shape is how this project's
 * worst bugs happened.
 */
class LiveScoreRepository(private val client: EspnClient) {

    /**
     * THE matchup refresh. One mMatchup call for rosters and points, one
     * public scoreboard call for real game state. Nothing else — this screen
     * does not need ownership, transactions or depth charts, and pulling them
     * made a pull-to-refresh cost six requests.
     */
    fun load(season: Int, leagueId: Long, scoringPeriod: Int): LiveScoreboard? {
        val url = client.leagueUrl(season, leagueId, "mMatchup") +
            "&view=mMatchupScore&scoringPeriodId=$scoringPeriod"
        val res = client.get(url)
        if (!res.ok) return null
        val board = runCatching { parse(res.body, scoringPeriod) }.getOrNull()
            ?: return null
        // Best effort: unverified endpoint, so a failure leaves the screen on
        // kickoff-time inference rather than breaking it.
        val games = runCatching {
            NflScoreboardClient().load(scoringPeriod, season)
        }.getOrDefault(emptyMap())
        return board.copy(games = games)
    }

    private fun parse(raw: String, scoringPeriod: Int): LiveScoreboard {
        val schedule = JSONObject(raw).optJSONArray("schedule")
        val byTeam = mutableMapOf<Int, LiveTeamScore>()

        for (i in 0 until (schedule?.length() ?: 0)) {
            val m = schedule?.optJSONObject(i) ?: continue
            if (m.optInt("matchupPeriodId", -1) != scoringPeriod) continue
            listOf("home", "away").forEach { side ->
                val obj = m.optJSONObject(side) ?: return@forEach
                val teamId = obj.optInt("teamId", -1)
                if (teamId < 0) return@forEach

                val roster = obj.optJSONObject("rosterForCurrentScoringPeriod")
                val entries = roster?.optJSONArray("entries")
                val points = mutableMapOf<Int, Double>()
                val slots = mutableMapOf<Int, Int>()
                for (e in 0 until (entries?.length() ?: 0)) {
                    val entry = entries?.optJSONObject(e) ?: continue
                    // D/ST ids are negative by design (-16000 - proTeamId),
                    // so a sign check silently dropped every defence. Guard on
                    // the key being absent, not on its value.
                    if (!entry.has("playerId")) continue
                    val id = entry.optInt("playerId")
                    if (id == 0) continue
                    val applied = entry.optJSONObject("playerPoolEntry")
                        ?.optDouble("appliedStatTotal", 0.0) ?: 0.0
                    points[id] = applied
                    slots[id] = entry.optInt("lineupSlotId", 20)
                }

                // totalPoints is the authoritative team score; the roster
                // total can lag it slightly mid-game.
                // totalPoints of 0.0 is a real score once a game is under
                // way, so only fall back when the field is missing entirely.
                val total = if (obj.has("totalPointsLive"))
                    obj.optDouble("totalPointsLive", 0.0)
                else if (obj.has("totalPoints")) obj.optDouble("totalPoints", 0.0)
                else roster?.optDouble("appliedStatTotal", 0.0) ?: 0.0

                byTeam[teamId] = LiveTeamScore(
                    teamId, total, points, slots,
                    livePoints = obj.optDouble("totalPointsLive", 0.0),
                    projectedLive = obj.optDouble(
                        "totalProjectedPointsLive",
                        obj.optDouble("totalProjectedPoints", 0.0)
                    ),
                    projectedPregame = obj.optDouble("totalProjectedPoints", 0.0),
                    winProbability = if (obj.has("winProbability"))
                        obj.optDouble("winProbability") else null
                )
            }
        }

        return LiveScoreboard(System.currentTimeMillis(), scoringPeriod, byTeam)
    }
}
