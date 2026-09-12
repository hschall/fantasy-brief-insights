package com.aviato.fantasybrief.data

data class Beneficiary(
    val playerId: Int,
    val name: String,
    val position: String,
    val proTeamId: Int,
    val depthRank: Int,
    val projection: Double?,
    val percentOwned: Double,
    val percentChange: Double,
    /** Who is unavailable ahead of him. */
    val blockedBy: String,
    val blockerTeamName: String?,
    val availability: String,
    val projectionLooksStale: Boolean
)

/**
 * Mechanically generated: for every unavailable player in the league, who is
 * ranked directly behind him on the NFL depth chart.
 *
 * This is what the app previously required a web search to answer. Kaleb
 * Johnson vs MarShawn Lloyd was exactly this question — an exempt-list
 * starter and two candidates whose relative standing lived only on a depth
 * chart nobody was reading.
 *
 * It does NOT decide who is right. It puts the candidates in front of you
 * with their depth ranks, and the search confirms which one the coaching
 * staff actually favours.
 */
object Beneficiaries {

    fun build(
        league: League,
        wire: List<WirePlayer>,
        depth: DepthCharts,
        pro: ProTeamIndex
    ): List<Beneficiary> {
        if (depth.teamsLoaded == 0) return emptyList()

        val ownerOf = league.teams
            .flatMap { t -> t.roster.map { it.playerId to t } }.toMap()
        val rostered = league.teams.flatMap { it.roster }

        // Everyone unavailable, wherever they sit.
        val blocked = rostered.filterNot { available(it) }

        val out = mutableListOf<Beneficiary>()
        blocked.forEach { out_player ->
            val backupId = depth.bestBehind(out_player.proTeamId, out_player.playerId)
                ?: return@forEach

            // The beneficiary may be on the wire OR on a rival's bench. The
            // second case is the more valuable one — nobody has repriced him.
            val onWire = wire.firstOrNull { it.playerId == backupId }
            val onRoster = rostered.firstOrNull { it.playerId == backupId }

            val name = onWire?.name ?: onRoster?.name ?: return@forEach
            val position = onWire?.position ?: onRoster?.position ?: return@forEach
            val projection = onWire?.projection ?: onRoster?.projection
            val owned = onWire?.percentOwned ?: onRoster?.percentOwned ?: 0.0
            val change = onWire?.percentChange ?: onRoster?.percentChange ?: 0.0
            val rank = depth.rank(out_player.proTeamId, backupId) ?: return@forEach

            out.add(
                Beneficiary(
                    playerId = backupId,
                    name = name,
                    position = position,
                    proTeamId = out_player.proTeamId,
                    depthRank = rank,
                    projection = projection,
                    percentOwned = owned,
                    percentChange = change,
                    blockedBy = out_player.name,
                    blockerTeamName = ownerOf[out_player.playerId]?.name,
                    availability = when {
                        onRoster != null && ownerOf[backupId] != null ->
                            "rostered by ${ownerOf[backupId]?.name}"
                        onWire?.status == "WAIVERS" -> "WAIVERS"
                        onWire != null -> "FREE AGENT"
                        else -> "unknown"
                    },
                    // The signal that started this project: a near-zero
                    // projection on a player who just inherited a role.
                    projectionLooksStale = (projection ?: 0.0) < 4.0
                )
            )
        }

        return out
            .distinctBy { it.playerId }
            .sortedWith(
                compareBy<Beneficiary> { it.depthRank }
                    .thenByDescending { it.percentChange }
            )
    }

    private fun available(p: RosterPlayer): Boolean {
        if ((p.projection ?: 0.0) == 0.0 && p.positionId != 16) return false
        val s = p.injuryStatus?.uppercase() ?: return true
        return !(s.contains("OUT") || s.contains("INJURY_RESERVE") ||
            s.contains("DOUBTFUL") || s.contains("SUSPENSION") ||
            s.contains("EXEMPT") || s.contains("NON_FOOTBALL"))
    }
}

/**
 * Ownership trajectory. A single delta says "moving"; a series says whether
 * the move is accelerating, steady, or already over.
 *
 * Kaleb Johnson's decay (+5.8 then falling, then dropped) was only visible by
 * putting four dumps side by side. Now it's one line.
 */
data class Trend(
    val playerId: Int,
    val points: List<Pair<Long, Double>>   // timestamp to percentOwned
) {
    val direction: String
        get() {
            if (points.size < 3) return ""
            val recent = points.takeLast(3).map { it.second }
            val firstStep = recent[1] - recent[0]
            val secondStep = recent[2] - recent[1]
            return when {
                secondStep > firstStep + 0.3 -> "accelerating"
                secondStep < firstStep - 0.3 && secondStep > 0 -> "slowing"
                secondStep < -0.3 -> "reversing"
                else -> "steady"
            }
        }

    /** Compact sparkline: "6.3 > 9.1 > 12.4". */
    fun render(): String {
        if (points.size < 2) return ""
        return points.takeLast(4)
            .joinToString(" > ") { String.format(java.util.Locale.US, "%.1f", it.second) } +
            (if (direction.isNotEmpty()) "  ($direction)" else "")
    }
}
