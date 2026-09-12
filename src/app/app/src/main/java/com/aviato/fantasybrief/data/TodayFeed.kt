package com.aviato.fantasybrief.data

/**
 * The one thing to do right now, and what it costs to ignore it.
 *
 * Derived from the existing research queue — same ranking, same inputs — but
 * carrying a computed point swing and a concrete fix, because a flag without
 * a number and a button is not an action.
 */
data class TopAction(
    val headline: String,
    val body: String,
    val pointSwing: Double,
    val urgent: Boolean,
    val ageText: String?,
    val deadlineText: String?,
    /** The player to move, when the fix is a lineup change. */
    val subject: RosterPlayer?,
    val fixLabel: String?,
    val fixTarget: RosterPlayer?,
    /** Set when the fix is an acquisition rather than a lineup change. */
    val acquireTarget: WirePlayer? = null,
    /** Other situations of the same kind, shown as one line beneath. */
    val alsoSee: List<String> = emptyList(),
    /** True when the injured starter is on MY roster. */
    val mineAtRisk: Boolean = false
)

/**
 * News about one player, with its effect on YOUR team expressed as a number.
 *
 * An update that cannot supply all four of who / what / effect / action is not
 * worth a card, so anything failing to produce a swing is dropped rather than
 * shown with a blank.
 */
data class PlayerUpdate(
    val player: RosterPlayer?,
    val wirePlayer: WirePlayer?,
    val playerId: Int,
    val name: String,
    val position: String,
    val proTeamId: Int,
    val depthLabel: String?,
    val what: String,
    val effect: Double,
    val relation: String,        // IN YOUR LINEUP | ON YOUR BENCH | FREE AGENT | HELPS YOU
    val actionLabel: String
) {
    val helpsYou: Boolean get() = relation == "HELPS YOU"
}

data class WireAlert(
    val player: WirePlayer,
    /** Set when this player was dropped by a rival and is worth having. */
    val blunder: BlunderInfo? = null,
    val tier: Tier,
    val gain: Double,
    val whyNow: String,
    val overName: String,
    val overProjection: Double,
    val overDescription: String
)

/**
 * A rival dropped someone good enough to start for me.
 *
 * The most valuable thing in the transaction log, and previously buried in it:
 * a drop is only a mistake relative to what the dropping team kept and what I
 * could do with the player. Both are computable.
 */
data class BlunderInfo(
    val droppedBy: String,
    val hoursAgo: Long,
    /** Where he'd rank against MY starters at his position. */
    val wouldStart: Boolean,
    val tierLabel: String?
)

object TodayFeed {

    /**
     * Highest-priority flag, resolved into an action.
     *
     * Uses the queue's own ordering rather than a second ranking — two places
     * disagreeing about what matters most is exactly the defect the note warns
     * about for standings, and it applies here too.
     */
    fun topAction(
        league: League,
        pro: ProTeamIndex,
        replacement: ReplacementLevel,
        flags: List<ResearchFlag>,
        week: Int
    ): TopAction? {
        val team = league.myTeam ?: return null

        // Handcuff situations live in their own AT RISK section now —
        // they are a different kind of thing from a lineup fix, and
        // collapsing them into one card lost the pairing that matters.

        // An unavailable player still in a starting slot        // An unavailable player still in a starting slot        // An unavailable player still in a starting slot is always the most
        // expensive thing on the roster, whatever the queue thinks.
        val broken = team.roster
            .filter { it.isStarter }
            .filter { !it.healthy || pro.isOnBye(it.proTeamId, week) }
            .filter { !pro.hasKickedOff(it.proTeamId, week) }
            .maxByOrNull { it.projection ?: 0.0 }

        // An unfilled starting slot scores zero and is trivially fixable.
        val emptySlot = league.settings.starterSlots.firstOrNull { rule ->
            team.roster.count { it.lineupSlotId == rule.slotId } < rule.count
        }
        if (emptySlot != null && broken == null) {
            val eligible = team.roster
                .filterNot { it.isStarter }
                .filter { it.eligibleSlots.contains(emptySlot.slotId) }
                .maxByOrNull { it.projection ?: 0.0 }
            return TopAction(
                headline = "Your ${Enums.slot(emptySlot.slotId)} slot is empty",
                body = eligible?.let {
                    "${it.name} projects ${fmt(it.projection ?: 0.0)} and is " +
                        "eligible for it."
                } ?: "Nothing on your bench is eligible. You need to add someone.",
                pointSwing = eligible?.projection ?: 0.0,
                urgent = true, ageText = null, deadlineText = null,
                subject = eligible, fixLabel = eligible?.let { "Start ${it.name}" },
                fixTarget = eligible
            )
        }

        if (broken != null) {
            val options = team.roster
                .filterNot { it.isStarter }
                .filter { it.eligibleSlots.contains(broken.lineupSlotId) }
                .filter { it.healthy && !pro.isOnBye(it.proTeamId, week) }
                .filter { !pro.hasKickedOff(it.proTeamId, week) }
                .sortedByDescending { it.projection ?: 0.0 }

            val best = options.firstOrNull()
            val gain = (best?.projection ?: 0.0) - (broken.projection ?: 0.0)
            val why = if (pro.isOnBye(broken.proTeamId, week)) "on bye"
                      else broken.injuryStatus?.lowercase() ?: "unavailable"

            return TopAction(
                headline = "${broken.name} is $why and still in your lineup",
                body = if (best == null)
                    "Nothing on your bench is eligible for " +
                        "${Enums.slot(broken.lineupSlotId)} and unlocked."
                else buildString {
                    append(best.name).append(" projects ")
                    append(fmt(best.projection ?: 0.0)).append(" in the same slot.")
                    options.getOrNull(1)?.let {
                        append(" ").append(it.name).append(" ")
                            .append(fmt(it.projection ?: 0.0)).append(".")
                    }
                    append(" Unlocked and on your bench.")
                    if (gain < 0) {
                        append(" That is ").append(fmt(-gain))
                        append(" fewer projected points — only worth it if he ")
                        append("genuinely sits. Check the practice report.")
                    }
                },
                pointSwing = if (best == null) -(broken.projection ?: 0.0) else gain,
                urgent = true,
                ageText = null,
                deadlineText = pro.kickoffLabel(broken.proTeamId, week)
                    ?.let { "KICKOFF $it" },
                subject = broken,
                // A negative swing means the replacement projects LOWER —
                // worth doing only if he genuinely does not play, which is a
                // news question. Do not dress that up as a recommendation.
                fixLabel = best?.let {
                    if (gain >= 0) "Swap to ${it.name.substringAfterLast(' ')}"
                    else "Swap anyway ${it.name.substringAfterLast(' ')}"
                },
                fixTarget = best
            )
        }

        // NOTHING ELSE QUALIFIES.
        //
        // This section previously fell through to the research queue's top
        // flag, which meant it rendered every single day — usually saying "a
        // wire riser exists". That is not an action, and a card that always
        // appears stops being read. It now shows only when the lineup is
        // actually broken: an unavailable starter, an empty slot, or a starter
        // on bye. Everything else lives in AT RISK or the wire.
        return null
    }

    fun playerUpdates(
        league: League,
        wire: List<WirePlayer>,
        pro: ProTeamIndex,
        depth: DepthCharts,
        replacement: ReplacementLevel,
        beneficiaries: List<Beneficiary>,
        week: Int,
        opponentTeamId: Int?
    ): List<PlayerUpdate> {
        val team = league.myTeam ?: return emptyList()
        val out = mutableListOf<PlayerUpdate>()

        // 1. My starter unavailable: cost is his projection minus the best
        //    eligible replacement, not simply his projection.
        team.roster.filter { it.isStarter }
            .filter { !it.healthy || pro.isOnBye(it.proTeamId, week) }
            .forEach { p ->
                val best = team.roster.filterNot { it.isStarter }
                    .filter { b -> b.eligibleSlots.contains(p.lineupSlotId) }
                    .filter { b -> b.healthy && !pro.isOnBye(b.proTeamId, week) }
                    .maxOfOrNull { b -> b.projection ?: 0.0 } ?: 0.0
                out.add(
                    PlayerUpdate(
                        player = p, wirePlayer = null, playerId = p.playerId,
                        name = p.name, position = p.position, proTeamId = p.proTeamId,
                        depthLabel = depth.label(p.proTeamId, p.playerId),
                        what = if (pro.isOnBye(p.proTeamId, week)) "On bye this week"
                               else "Ruled ${p.injuryStatus?.lowercase()}",
                        effect = best - (p.projection ?: 0.0),
                        relation = "IN YOUR LINEUP",
                        actionLabel = "Fix lineup"
                    )
                )
            }

        // 2. Bench player who would actually improve the lineup.
        //
        // The old rule found "the weakest starter he is eligible to replace"
        // and stopped there — which surfaced a backup QB as news because he
        // outprojects a flex WR, ignoring that starting him means benching the
        // starting QB. A promotion is only news if a SPECIFIC swap is legal and
        // comes out ahead.
        team.roster.filterNot { it.isStarter }.forEach { p ->
            if (!p.healthy || pro.isOnBye(p.proTeamId, week)) return@forEach

            // Mutual eligibility, same rule the lineup sheet enforces: both
            // players must be able to hold the other's slot.
            val displaceable = team.roster.filter { st ->
                st.isStarter &&
                    p.eligibleSlots.contains(st.lineupSlotId) &&
                    st.eligibleSlots.contains(p.lineupSlotId)
            }
            val worst = displaceable.minByOrNull { it.projection ?: 0.0 } ?: return@forEach
            val gain = (p.projection ?: 0.0) - (worst.projection ?: 0.0)
            if (gain <= 1.0) return@forEach

            val rank = depth.rank(p.proTeamId, p.playerId)
            out.add(
                PlayerUpdate(
                    player = p, wirePlayer = null, playerId = p.playerId,
                    name = p.name, position = p.position, proTeamId = p.proTeamId,
                    depthLabel = depth.label(p.proTeamId, p.playerId),
                    what = (if (rank == 1) "Top of his depth chart. " else "") +
                        "Outprojects ${worst.name} " +
                        "(${fmt(worst.projection ?: 0.0)}) in your " +
                        "${Enums.slot(worst.lineupSlotId)} slot.",
                    effect = gain, relation = "ON YOUR BENCH",
                    actionLabel = "Start him"
                )
            )
        }

        // 3. Free agent who would displace a specific starter. Same test:
        // the swap has to be legal and net-positive, not merely "better than
        // someone at that position".
        wire.filter { it.percentChange >= 1.0 || it.isMoneySignal }.forEach { w ->
            val displaceable = team.roster.filter { st ->
                st.isStarter && st.position == w.position
            }
            val worst = displaceable.minByOrNull { it.projection ?: 0.0 } ?: return@forEach
            val gain = (w.projection ?: 0.0) - (worst.projection ?: 0.0)
            if (gain <= 1.0) return@forEach
            out.add(
                PlayerUpdate(
                    player = null, wirePlayer = w, playerId = w.playerId,
                    name = w.name, position = w.position, proTeamId = w.proTeamId,
                    depthLabel = depth.label(w.proTeamId, w.playerId),
                    what = "Ownership ${signedFmt(w.percentChange)} and available. " +
                        "Would start over ${worst.name} " +
                        "(${fmt(worst.projection ?: 0.0)}).",
                    effect = gain, relation = "FREE AGENT",
                    actionLabel = "Claim"
                )
            )
        }

        // Opponent news deliberately excluded — it is not something you
        // can act on, and it crowded out items that are.

        return out
            .distinctBy { it.playerId to it.relation }
            .sortedByDescending { kotlin.math.abs(it.effect) }
            .take(5)
    }

    /**
     * Wire cards. The argument is the upgrade and WHY NOW, never the tier
     * alone — a tier is a standing, not a reason to act today.
     */
    fun wireAlerts(
        league: League,
        tiers: Map<Tier, List<TieredPlayer>>,
        transactions: List<Transaction>,
        depth: DepthCharts,
        playerNames: Map<Int, String>,
        week: Int
    ): List<WireAlert> {
        val team = league.myTeam ?: return emptyList()
        val recent = System.currentTimeMillis() - 24L * 3600 * 1000
        val myWaiver = team.waiverRank

        val candidates = (tiers[Tier.ELITE].orEmpty() + tiers[Tier.SOLID].orEmpty())
        return candidates.mapNotNull { t ->
            val p = t.player
            val weakest = team.roster
                .filter { it.isStarter && it.position == p.position }
                .minByOrNull { it.projection ?: 0.0 } ?: return@mapNotNull null
            val gain = (p.projection ?: 0.0) - (weakest.projection ?: 0.0)
            if (gain <= 0.0) return@mapNotNull null

            // Reasons we can actually evidence. A failed rival claim leaves no
            // trace in ESPN's log, so it is deliberately absent.
            val dropped = transactions.firstOrNull {
                it.playerId == p.playerId && it.kind == TxKind.DROP &&
                    it.whenMillis >= recent
            }
            val rivalsAhead = league.teams
                .filter { it.waiverRank < myWaiver && it.id != team.id }
                .count { rival ->
                    transactions.any { tx ->
                        tx.teamId == rival.id && tx.whenMillis >= recent &&
                            (tx.kind == TxKind.ADD || tx.kind == TxKind.WAIVER_ADD)
                    }
                }
            val rank = depth.rank(p.proTeamId, p.playerId)

            val why = when {
                dropped != null -> {
                    val by = league.teams.firstOrNull { it.id == dropped.teamId }?.name
                    "Dropped ${hoursAgo(dropped.whenMillis)} ago" +
                        (by?.let { " by $it" } ?: "") + "."
                }
                rank != null && rank <= 2 && (p.projection ?: 0.0) < 4.0 ->
                    "Depth rank $rank with a projection that has not caught up."
                rivalsAhead > 0 ->
                    "$rivalsAhead manager(s) ahead of you on waivers made a move today."
                p.percentChange >= 1.5 ->
                    "Ownership ${signedFmt(p.percentChange)} while still at " +
                        "${fmt(p.percentOwned)}%."
                else -> "Clears the median ${p.position} starter in this league."
            }

            // A rival's drop is a blunder only if the player would improve
            // MY lineup. Dropping a bad player is just housekeeping.
            val blunder = dropped?.let { d ->
                val by = league.teams.firstOrNull { it.id == d.teamId }
                if (by == null || by.id == team.id) return@let null
                BlunderInfo(
                    droppedBy = by.name,
                    hoursAgo = (System.currentTimeMillis() - d.whenMillis) / 3_600_000,
                    wouldStart = gain > 0.0,
                    tierLabel = t.tier.label
                )
            }

            WireAlert(
                player = p, blunder = blunder,
                tier = t.tier, gain = gain, whyNow = why,
                overName = weakest.name,
                overProjection = weakest.projection ?: 0.0,
                overDescription = "your weakest ${weakest.position}"
            )
        }.sortedWith(
            // A dropped player is claimable right now and someone else is
            // looking at the same log, so these outrank a steady riser.
            compareByDescending<WireAlert> { it.blunder != null }
                .thenByDescending { it.gain }
        ).take(4)
    }

    private fun hoursAgo(millis: Long): String {
        val h = (System.currentTimeMillis() - millis) / 3_600_000
        return if (h < 1) "under an hour" else "${h}h"
    }

    /** Designations that mean he will not play, or probably will not. */
    private fun isSevere(status: String?): Boolean {
        val s = status?.uppercase() ?: return false
        return s.contains("OUT") || s.contains("INJURY_RESERVE") ||
            s.contains("DOUBTFUL") || s.contains("SUSPENSION") ||
            s.contains("EXEMPT") || s.contains("NON_FOOTBALL") ||
            s.contains("QUESTIONABLE")
    }

    private fun label(status: String?): String = when {
        status == null -> "unavailable"
        status.uppercase().contains("INJURY_RESERVE") -> "on IR"
        status.uppercase().contains("QUESTIONABLE") -> "questionable"
        else -> status.lowercase().replace('_', ' ')
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun signedFmt(v: Double) =
        if (v >= 0) String.format(java.util.Locale.US, "+%.1f", v)
        else String.format(java.util.Locale.US, "\u2212%.1f", -v)
}
