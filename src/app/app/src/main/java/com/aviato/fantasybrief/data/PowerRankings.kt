package com.aviato.fantasybrief.data

data class StandingRow(
    val rank: Int,
    val team: FantasyTeam,
    val managerName: String?,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val pointsFor: Double,
    val power: Double?,
    /** Change in power since the last recorded reading. Null on a first run. */
    val powerDelta: Double?
) {
    val record: String get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
}

data class PowerSeries(
    val teamId: Int,
    /** week to power value, ascending. */
    val points: List<Pair<Int, Double>>
)

/**
 * League standing and momentum, derived from the full schedule.
 *
 * Rank is computed ONCE here and every screen reads it. Two places claiming a
 * different rank for the same team is the one defect this cannot survive, so
 * there is deliberately no second derivation anywhere.
 */
object PowerRankings {

    private const val MIN_WEEKS_FOR_CHART = 3

    /** False before any game is played — the table is projections then. */
    fun hasResults(league: League): Boolean =
        league.teams.any { it.wins + it.losses + it.ties > 0 }

    /** teamId -> week -> points scored that week. */
    fun weeklyScores(schedule: List<Matchup>, throughWeek: Int): Map<Int, Map<Int, Double>> {
        val out = mutableMapOf<Int, MutableMap<Int, Double>>()
        schedule.filter { it.matchupPeriodId in 1..throughWeek }.forEach { m ->
            // A future or unplayed week reports 0.0 for both sides; excluding
            // those keeps the average from being dragged toward zero.
            if (m.homePoints > 0.0) {
                out.getOrPut(m.homeTeamId) { mutableMapOf() }[m.matchupPeriodId] = m.homePoints
            }
            if (m.awayPoints > 0.0) {
                out.getOrPut(m.awayTeamId) { mutableMapOf() }[m.matchupPeriodId] = m.awayPoints
            }
        }
        return out
    }

    /** power[w] = (pts[w] + pts[w-1]) / 2, for every week both exist. */
    fun series(scores: Map<Int, Map<Int, Double>>): Map<Int, PowerSeries> =
        scores.mapValues { (teamId, byWeek) ->
            val weeks = byWeek.keys.sorted()
            PowerSeries(
                teamId,
                weeks.mapNotNull { w ->
                    val now = byWeek[w] ?: return@mapNotNull null
                    val prev = byWeek[w - 1] ?: return@mapNotNull null
                    w to (now + prev) / 2.0
                }
            )
        }

    /** True when there is enough history to draw a line rather than a stub. */
    fun chartable(series: Map<Int, PowerSeries>): Boolean =
        series.values.maxOfOrNull { it.points.size } ?: 0 >= MIN_WEEKS_FOR_CHART - 1

    fun standings(
        league: League,
        series: Map<Int, PowerSeries>,
        previousPower: Map<Int, Double>?
    ): List<StandingRow> {
        // Before any games, wins and points-for are all zero and the sort
        // degenerates to ESPN's array order — which would present noise as a
        // ranking. Fall back to projected rank until results exist.
        val played = league.teams.any { it.wins + it.losses + it.ties > 0 }
        val ranked = if (played) {
            league.teams.sortedWith(
                compareByDescending<FantasyTeam> { it.wins }
                    .thenByDescending { it.pointsFor }
            )
        } else {
            league.teams.sortedBy { if (it.projectedRank > 0) it.projectedRank else 99 }
        }
        return ranked.mapIndexed { index, team ->
            val power = series[team.id]?.points?.lastOrNull()?.second
            StandingRow(
                rank = index + 1,
                team = team,
                managerName = null,
                wins = team.wins,
                losses = team.losses,
                ties = team.ties,
                pointsFor = team.pointsFor,
                power = power,
                // Null, never 0.0 — "no baseline" and "unchanged" are
                // different facts and must not render alike.
                powerDelta = if (power == null || previousPower == null) null
                             else previousPower[team.id]?.let { power - it }
            )
        }
    }
}
