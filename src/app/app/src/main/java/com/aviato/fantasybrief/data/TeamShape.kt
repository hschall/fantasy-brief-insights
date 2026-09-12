package com.aviato.fantasybrief.data

/** What a roster is long and short of — the input to every trade idea. */
data class TeamShape(
    val team: FantasyTeam,
    val surplus: List<String>,
    val holes: List<String>,
    val worstByeWeek: Pair<Int, Int>?,
    val startingTotal: Double
)

object TeamShapes {

    fun build(
        league: League,
        pro: ProTeamIndex,
        replacement: ReplacementLevel,
        week: Int
    ): List<TeamShape> = league.teams.map { team ->
        val starters = team.roster.filter { it.isStarter }
        val bench = team.roster.filterNot { it.isStarter }

        val surplus = mutableListOf<String>()
        val holes = mutableListOf<String>()

        // A bench player who outprojects a starter at the same position is
        // either a lineup error or a tradeable asset. Either way it's the
        // most useful single fact about a rival roster.
        bench.groupBy { it.position }.forEach { (position, players) ->
            val weakest = starters.filter { it.position == position }
                .minByOrNull { it.projection ?: 0.0 }
            val best = players.maxByOrNull { it.projection ?: 0.0 } ?: return@forEach
            if (weakest != null && (best.projection ?: 0.0) > (weakest.projection ?: 0.0)) {
                surplus.add("${best.name} benched above ${weakest.name}")
            }
        }

        // Startable depth beyond the lineup requirement.
        val required = league.settings.starterSlots
            .filter { Enums.slotEligibility(it.slotId) == null }
            .associate { Enums.slot(it.slotId) to it.count }
        team.roster.groupBy { it.position }.forEach { (position, players) ->
            val need = required[position] ?: 0
            val startable = players.count {
                replacement.rank(position, it.projection) != "BELOW"
            }
            if (need > 0 && startable > need + 1) {
                surplus.add("$startable startable $position for $need slot(s)")
            }
        }

        // Unfilled slots and below-replacement starters.
        league.settings.starterSlots.forEach { rule ->
            val filled = starters.count { it.lineupSlotId == rule.slotId }
            if (filled < rule.count) {
                holes.add("no ${Enums.slot(rule.slotId)} rostered")
            }
        }
        starters.forEach { p ->
            if (replacement.rank(p.position, p.projection) == "BELOW") {
                holes.add("${p.position} ${p.name} below replacement")
            }
        }

        val worstBye = (week..17)
            .map { w -> w to team.roster.count { pro.isOnBye(it.proTeamId, w) } }
            .filter { it.second >= 3 }
            .maxByOrNull { it.second }

        TeamShape(
            team = team,
            surplus = surplus.take(3),
            holes = holes.take(3),
            worstByeWeek = worstBye,
            startingTotal = starters.sumOf { it.projection ?: 0.0 }
        )
    }.sortedByDescending { it.startingTotal }
}
