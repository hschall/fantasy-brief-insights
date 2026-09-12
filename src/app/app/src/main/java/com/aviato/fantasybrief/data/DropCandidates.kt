package com.aviato.fantasybrief.data

data class DropCandidate(
    val player: RosterPlayer,
    val flags: List<String>
)

/**
 * Enumerates every droppable player with the mechanical fields needed to
 * compare them, so an omission is conspicuous.
 *
 * Deliberately NOT a ranking. The criteria that actually decide a drop —
 * path to volume, offensive environment — are not derivable from ESPN data,
 * and any weighting would encode a judgment call as a constant. The block
 * supplies the comparison; it does not make it.
 */
object DropCandidates {

    /** Never offered as a drop. Short and real beats complete and ignored. */
    private val LOCKED = setOf("Josh Allen", "De'Von Achane", "Justin Jefferson")

    fun build(
        league: League,
        pro: ProTeamIndex,
        replacement: ReplacementLevel,
        week: Int
    ): List<DropCandidate> {
        val team = league.myTeam ?: return emptyList()
        val roster = team.roster

        // Weeks where this roster is already thin — a player whose bye lands
        // here is worth less than his projection suggests.
        val clusterWeeks = (week..17).filter { w ->
            roster.count { pro.isOnBye(it.proTeamId, w) } >= 3
        }.toSet()

        val countByPosition = roster.groupingBy { it.position }.eachCount()
        val startedSlots = league.settings.starterSlots.map { it.slotId }.toSet()

        return roster
            .filterNot { it.name in LOCKED }
            // Droppable = bench, plus any starter projecting below the
            // WORST starting player at that position league-wide. Filtering on
            // the median instead put a 13.8 WR in the drop list, which is
            // absurd — half of every position is below its own median.
            .filter { p ->
                when {
                    !p.isStarter -> true
                    // A position you carry exactly one of is ALWAYS droppable:
                    // adding another kicker or defence means dropping the one
                    // you have, and excluding him left no legal drop at all.
                    (countByPosition[p.position] ?: 0) <= 1 -> true
                    else -> replacement.rank(p.position, p.projection) == "BELOW"
                }
            }
            .map { p ->
                // distinct(): two rules were adding ONLY-AT-POS.
                val flags = buildList {
                    if ((p.percentOwned ?: 0.0) > 50.0) add("OWN>50")
                    if (p.isStarter && (countByPosition[p.position] ?: 0) <= 1) {
                        add("ONLY-AT-POS")
                    }

                    pro.byeWeek(p.proTeamId)?.let { bye ->
                        if (bye in clusterWeeks) add("BYE$bye-CLUSTER")
                    }

                    // Last body at a position the lineup requires.
                    val needsPosition = startedSlots.any { slot ->
                        p.eligibleSlots.contains(slot)
                    }
                    if (needsPosition && (countByPosition[p.position] ?: 0) <= 1) {
                        add("ONLY-AT-POS")
                    }

                    // Projects above someone currently in the lineup at the
                    // same position — dropping him is backwards.
                    val weakestStarter = roster
                        .filter { it.isStarter && it.position == p.position }
                        .minOfOrNull { it.projection ?: 0.0 }
                    if (!p.isStarter && weakestStarter != null &&
                        (p.projection ?: 0.0) > weakestStarter
                    ) add("PROJ>STARTER")

                    if (p.percentOwned == null) add("OWN-UNKNOWN")

                    // A bench player who would START for another team is
                    // currency, not a cut. Compare against the LOWEST starter
                    // at his position league-wide, not the median — Stafford
                    // at 17.1 sits below the QB median yet would upgrade four
                    // of the ten starting lineups.
                    val solid = replacement.solid(p.position)
                    if (!p.isStarter && solid != null &&
                        (p.projection ?: 0.0) > solid
                    ) add("TRADE-ASSET")

                    // Only one rostered at a position the lineup starts, and
                    // he is not currently in it — usually a lineup error.
                    if (!p.isStarter && (countByPosition[p.position] ?: 0) <= 1 &&
                        league.settings.starterSlots.any { rule ->
                            p.eligibleSlots.contains(rule.slotId)
                        }
                    ) add("UNUSED-STARTER")
                }.distinct()
                DropCandidate(p, flags)
            }
            // Ascending by projection for scannability only. A sorted list
            // anchors the reader on row one, which is the shortcut this block
            // exists to prevent — hence the header labels it presentation order.
            .sortedBy { it.player.projection ?: 0.0 }
    }
}
