package com.aviato.fantasybrief.data

import org.json.JSONObject

data class Matchup(
    val matchupPeriodId: Int,
    val homeTeamId: Int,
    val awayTeamId: Int,
    val homePoints: Double,
    val awayPoints: Double,
    val winner: String
)

/**
 * mMatchup returns the FULL season schedule — 70 entries for a 14-week
 * regular season plus playoffs. The response is ~686KB, so parse only what
 * the current period needs and never hold the raw body.
 */
object MatchupParser {

    /**
     * Every period in the response, not just the current one.
     *
     * The 70-entry schedule was already being fetched and thrown away. Each
     * entry carries home/away totalPoints, which is the whole season's scoring
     * history for all ten teams — enough for power rankings with no new
     * request.
     */
    fun parseAll(raw: String): List<Matchup> {
        val schedule = JSONObject(raw).optJSONArray("schedule") ?: return emptyList()
        return buildList {
            for (i in 0 until schedule.length()) {
                val m = schedule.optJSONObject(i) ?: continue
                val home = m.optJSONObject("home") ?: continue
                val away = m.optJSONObject("away") ?: continue
                add(
                    Matchup(
                        matchupPeriodId = m.optInt("matchupPeriodId", -1),
                        homeTeamId = home.optInt("teamId", -1),
                        awayTeamId = away.optInt("teamId", -1),
                        homePoints = home.optDouble("totalPoints", 0.0),
                        awayPoints = away.optDouble("totalPoints", 0.0),
                        winner = m.optString("winner", "UNDECIDED")
                    )
                )
            }
        }
    }

    fun parse(raw: String, matchupPeriodId: Int): List<Matchup> {
        val schedule = JSONObject(raw).optJSONArray("schedule") ?: return emptyList()
        return buildList {
            for (i in 0 until schedule.length()) {
                val m = schedule.optJSONObject(i) ?: continue
                if (m.optInt("matchupPeriodId", -1) != matchupPeriodId) continue
                val home = m.optJSONObject("home") ?: continue
                val away = m.optJSONObject("away") ?: continue
                add(
                    Matchup(
                        matchupPeriodId = matchupPeriodId,
                        homeTeamId = home.optInt("teamId", -1),
                        awayTeamId = away.optInt("teamId", -1),
                        homePoints = home.optDouble("totalPoints", 0.0),
                        awayPoints = away.optDouble("totalPoints", 0.0),
                        winner = m.optString("winner", "UNDECIDED")
                    )
                )
            }
        }
    }
}

data class PendingClaim(
    val playerId: Int,
    val transactionIds: List<String>
)

/**
 * Pending waiver claims.
 *
 * mPendingTransactions returns HTTP 200 with an EMPTY ENVELOPE when nothing
 * is pending — that is correct, not a failure and not a cookie problem.
 * mTeam does NOT carry a pendingTransactions field in 2026 despite community
 * docs saying so; verified by depth-8 probe.
 *
 * The reliable signal is rosterEntry.pendingTransactionIds on mRoster, which
 * is non-null on players involved in an in-flight claim.
 */
object PendingParser {

    fun fromRoster(raw: String, myTeamId: Int?): List<PendingClaim> {
        if (myTeamId == null) return emptyList()
        val teams = JSONObject(raw).optJSONArray("teams") ?: return emptyList()
        for (t in 0 until teams.length()) {
            val team = teams.optJSONObject(t) ?: continue
            if (team.optInt("id", -1) != myTeamId) continue
            val entries = team.optJSONObject("roster")?.optJSONArray("entries")
                ?: return emptyList()
            return buildList {
                for (e in 0 until entries.length()) {
                    val entry = entries.optJSONObject(e) ?: continue
                    val ids = entry.optJSONArray("pendingTransactionIds") ?: continue
                    if (ids.length() == 0) continue
                    add(
                        PendingClaim(
                            playerId = entry.optInt("playerId", -1),
                            transactionIds = buildList {
                                for (i in 0 until ids.length()) add(ids.optString(i))
                            }
                        )
                    )
                }
            }
        }
        return emptyList()
    }
}
