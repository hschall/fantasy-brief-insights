package com.aviato.fantasybrief.data

/**
 * An NFL starter who may not play, and the man behind him who is unowned.
 *
 * This is the highest-leverage pattern the app can see: it takes a depth
 * chart, an injury designation, and league-wide rostership, none of which is
 * useful alone. A rival's hurt RB1 with a free backup is a starting NFL job
 * available for a waiver claim.
 */
data class AtRiskPair(
    val starter: RosterPlayer,
    val ownerTeamId: Int,
    val ownerName: String,
    val isMine: Boolean,
    val backup: WirePlayer,
    val backupDepthRank: Int?,
    val proTeamId: Int,
    /** Volume at stake: what the starter was projected to produce. */
    val vacating: Double,
    val onWaivers: Boolean,
    /** 0-100. NOT a probability — a ranking of how settled the label is. */
    val certainty: Int,
    val certaintyLabel: String
) {

    /** The market is already moving on him — others believe the news. */
    val backupTrending: Boolean get() = backup.percentChange >= 1.5
    val designation: String get() = starter.injuryStatus ?: "unavailable"
}

object AtRisk {

    fun find(
        league: League,
        wire: List<WirePlayer>,
        depth: DepthCharts,
        week: Int
    ): List<AtRiskPair> {
        val out = mutableListOf<AtRiskPair>()

        league.teams.forEach { owner ->
            owner.roster
                .filter { severe(it.injuryStatus) || (it.projection ?: 0.0) == 0.0 }
                // RB and WR only. A backup QB inherits the job but rarely
                // the value, and a TE2 inherits almost no targets — both
                // produced cards nobody acts on, crowding out the ones you do.
                .filter { it.positionId == 2 || it.positionId == 3 }
                // FIRST ON HIS NFL CHART, not "in someone's fantasy lineup".
                // A hurt RB1 vacates real work wherever his manager slotted
                // him; a hurt RB3 vacates nothing even from a flex.
                .filter { depth.rank(it.proTeamId, it.playerId) == 1 }
                .forEach { hurt ->
                    val backupId = depth.backupTo(hurt.proTeamId, hurt.playerId)
                        ?: return@forEach
                    val free = wire.firstOrNull { it.playerId == backupId }
                        ?: return@forEach
                    out.add(
                        AtRiskPair(
                            starter = hurt,
                            ownerTeamId = owner.id,
                            ownerName = owner.name,
                            isMine = owner.id == league.myTeamId,
                            backup = free,
                            backupDepthRank = depth.rank(hurt.proTeamId, free.playerId),
                            proTeamId = hurt.proTeamId,
                            vacating = hurt.projection ?: 0.0,
                            onWaivers = free.status == "WAIVERS",
                            certainty = certaintyOf(hurt),
                            certaintyLabel = certaintyLabel(hurt)
                        )
                    )
                }
        }

        // Mine first — a hole in my lineup costs points this Sunday, while a
        // rival's is speculation. Then by volume at stake.
        return out
            .distinctBy { it.backup.playerId }
            .sortedWith(
                // Yours first — a hole in your lineup costs points this
                // Sunday. Then by how SETTLED the absence is, because a
                // ruled-out starter's backup is a different proposition
                // from a questionable one's. Volume breaks the tie.
                compareByDescending<AtRiskPair> { it.isMine }
                    .thenByDescending { it.certainty }
                    // At equal certainty, the one others are chasing is the
                    // one whose window closes first.
                    .thenByDescending { it.backupTrending }
                    .thenByDescending { it.vacating }
            )
    }

    /**
     * How settled the absence is, NOT a probability.
     *
     * ESPN gives a label, not a number, and QUESTIONABLE spans a Friday rest
     * day and a genuine coin flip. Turning that into "68% to miss" would be
     * inventing precision. What the labels do carry is a real ordering, and
     * that is all this claims to be.
     */
    private fun certaintyOf(p: RosterPlayer): Int {
        val s = p.injuryStatus?.uppercase() ?: ""
        return when {
            s.contains("INJURY_RESERVE") || s.contains("NON_FOOTBALL") -> 100
            s.contains("SUSPENSION") || s.contains("EXEMPT") -> 95
            s.contains("OUT") -> 90
            s.contains("DOUBTFUL") -> 70
            // No label but ESPN stopped projecting him — usually means
            // something they have not tagged yet.
            (p.projection ?: 0.0) == 0.0 -> 60
            s.contains("QUESTIONABLE") -> 40
            else -> 20
        }
    }

    private fun certaintyLabel(p: RosterPlayer): String {
        val s = p.injuryStatus?.uppercase() ?: ""
        return when {
            s.contains("INJURY_RESERVE") -> "Out for weeks"
            s.contains("NON_FOOTBALL") -> "Off the roster"
            s.contains("SUSPENSION") || s.contains("EXEMPT") -> "Cannot play"
            s.contains("OUT") -> "Ruled out"
            s.contains("DOUBTFUL") -> "Unlikely to play"
            (p.projection ?: 0.0) == 0.0 -> "Not projected"
            s.contains("QUESTIONABLE") -> "Genuinely uncertain"
            else -> "Flagged"
        }
    }

    private fun severe(status: String?): Boolean {
        val s = status?.uppercase() ?: return false
        return s.contains("OUT") || s.contains("INJURY_RESERVE") ||
            s.contains("DOUBTFUL") || s.contains("SUSPENSION") ||
            s.contains("EXEMPT") || s.contains("NON_FOOTBALL") ||
            s.contains("QUESTIONABLE")
    }
}
