package com.aviato.fantasybrief.data

enum class Tier(val label: String) {
    ELITE("ELITE"),
    SOLID("SOLID"),
    LOTTERY("LOTTERY TICKET"),
    HANDCUFF("MY HANDCUFF"),
    STREAMER("STREAMER")
}

data class TieredPlayer(
    val player: WirePlayer,
    val tier: Tier,
    val flags: List<String>,
    val note: String?
)

/**
 * Sorts the wire against THIS league rather than against absolute numbers.
 *
 * The two flat views this replaces both failed the same way: a stable,
 * high-projection player nobody in a shallow league drafted was invisible.
 * He has no ownership delta because he is 90% owned nationally, and the
 * projection view was capped and QB-less. Ranking against replacement level
 * catches him, because the only question that matters is whether he would
 * improve one of the ten starting lineups.
 */
object WireTiers {

    private const val LOTTERY_MAX_OWNED = 15.0
    private const val LOTTERY_MIN_DELTA = 1.5
    private const val HANDCUFF_MIN_PROJ = 3.0
    // A bench of five cannot use six tight ends. Cap per position so one
    // position cannot crowd out the rest of a tier.
    private const val MAX_PER_POSITION = 4

    fun build(
        wire: List<WirePlayer>,
        league: League,
        replacement: ReplacementLevel,
        pro: ProTeamIndex,
        depth: DepthCharts,
        week: Int
    ): Map<Tier, List<TieredPlayer>> {
        val mine = league.myTeam?.roster.orEmpty()

        // Weeks this roster is already thin — a player who shares that bye
        // solves less than his projection implies.
        val clusterWeeks = (week..17).filter { w ->
            mine.count { pro.isOnBye(it.proTeamId, w) } >= 3
        }.toSet()

        // Real handcuffs, from the depth chart: the player ranked directly
        // behind someone I roster. Replaces the jersey-match heuristic that
        // produced a WR3 "backing up" Jefferson.
        // RB ONLY. A "WR handcuff" is not a real thing — the depth-chart
        // version briefly produced a WR2 behind Garrett Wilson, which is just
        // another wide receiver on the same team.
        val myHandcuffIds = mine.filter { it.positionId == 2 }.mapNotNull { p ->
            depth.backupTo(p.proTeamId, p.playerId)
        }.toSet()
        val myRbTeams = mine.filter { it.positionId == 2 }.map { it.proTeamId }.toSet()

        val out = wire.mapNotNull { p ->
            // A handcuff shares his starter's bye BY DEFINITION — same NFL
            // team. Flagging that as a clash inverts the meaning: covering the
            // week your starter is out is the entire reason to hold him.
            val isHandcuff = (p.positionId == 2 && p.playerId in myHandcuffIds) ||
                (depth.teamsLoaded == 0 && p.positionId == 2 &&
                    p.proTeamId in myRbTeams)
            val flags = buildList {
                pro.byeWeek(p.proTeamId)?.let {
                    if (it in clusterWeeks && !isHandcuff) add("BYE$it-CLUSTER")
                }
                if (p.status == "WAIVERS") add("WAIVERS") else add("FA")
                depth.label(p.proTeamId, p.playerId)?.let { add(it) }
                // THE Kaleb Johnson CASE: a near-zero projection on a player
                // who is first or second on his depth chart means the
                // projection is stale, not that the player is bad.
                val r = depth.rank(p.proTeamId, p.playerId)
                if (r != null && r <= 2 && (p.projection ?: 0.0) < 3.0) {
                    add("PROJ-STALE")
                }
                if (!p.healthy) add(p.injuryTag)
            }

            val tier = when {
                // K and DST are matchup-driven and flooded the projection
                // list — nine of twenty rows. Split out entirely.
                p.positionId == 5 || p.positionId == 16 -> Tier.STREAMER
                replacement.rank(p.position, p.projection) == "ELITE" -> Tier.ELITE
                replacement.rank(p.position, p.projection) == "SOLID" -> Tier.SOLID
                p.percentOwned < LOTTERY_MAX_OWNED &&
                    p.percentChange >= LOTTERY_MIN_DELTA -> Tier.LOTTERY
                isHandcuff && (p.projection ?: 0.0) >= HANDCUFF_MIN_PROJ ->
                    Tier.HANDCUFF
                else -> null
            } ?: return@mapNotNull null

            val note = when (tier) {
                Tier.ELITE -> replacement.elite(p.position)?.let {
                    "clears the ${p.position} median starter (${fmt(it)})"
                }
                Tier.LOTTERY -> "value depends on news that hasn't happened yet"
                Tier.HANDCUFF -> {
                    val aheadId = depth.startsAheadOf(p.proTeamId, p.playerId)
                    mine.firstOrNull { it.playerId == aheadId }
                        ?.let { "ranked directly behind ${it.name}" }
                        ?: mine.firstOrNull {
                            it.proTeamId == p.proTeamId && it.positionId == 2
                        }?.let { "same backfield as ${it.name}" }
                }
                else -> null
            }

            TieredPlayer(p, tier, flags, note)
        }

        return Tier.entries.associateWith { tier ->
            out.filter { it.tier == tier }
                .sortedWith(
                    when (tier) {
                        // Lottery is a bet on momentum, so rank by delta.
                        Tier.LOTTERY -> compareByDescending { it.player.percentChange }
                        // When a whole position projects 0.0, ownership is the
                        // only signal left — it's what the market thinks.
                        Tier.STREAMER -> compareByDescending {
                            if ((it.player.projection ?: 0.0) > 0.0)
                                it.player.projection ?: 0.0
                            else it.player.percentOwned / 100.0
                        }
                        else -> compareByDescending { it.player.projection ?: 0.0 }
                    }
                )
                .let { list ->
                    // Cap per position, then overall.
                    val seen = mutableMapOf<String, Int>()
                    list.filter { t ->
                        val n = seen.getOrDefault(t.player.position, 0)
                        if (n >= MAX_PER_POSITION) false
                        else { seen[t.player.position] = n + 1; true }
                    }
                }
                .take(if (tier == Tier.STREAMER) 5 else 10)
        }.filterValues { it.isNotEmpty() }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
