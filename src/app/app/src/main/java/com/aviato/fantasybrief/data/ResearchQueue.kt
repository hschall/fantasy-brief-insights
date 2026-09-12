package com.aviato.fantasybrief.data

enum class FlagType(val label: String, val rank: Int) {
    TEAM_CHANGE("TEAM CHANGE", 0),
    MY_INJURY("MY ROSTER INJURY", 1),
    TRADE_TARGET("TRADE TARGET", 2),
    OWNERSHIP_SPIKE("OWNERSHIP SPIKE", 3),
    WIRE_RISER("WIRE RISER", 4),
    ROSTER_MOVE("ROSTER MOVE", 5),
    OTHER_INJURY("INJURY", 6),
    MY_STARTER("MY STARTER", 7)
}

data class ResearchFlag(
    val type: FlagType,
    val playerId: Int,
    val text: String,
    /** Lower sorts first within a priority band. */
    val weight: Double,
    /**
     * Sort band. Defaults to the type's rank, but an individual flag can be
     * promoted — an injury with a named beneficiary is actionable today and
     * should not sit below six ownership blips.
     */
    val priority: Int = type.rank
)

object ResearchQueue {

    private const val OWNERSHIP_SPIKE_POINTS = 4.0
    private const val WIRE_RISER_DELTA = 1.5
    private const val WIRE_RISER_MAX_OWNED = 20.0
    private const val MY_STARTER_FROM_WEEK = 2
    private const val MAX_TRADE_TARGETS = 8
    private const val CAP = 40

    fun generate(
        league: League,
        wire: List<WirePlayer>,
        pro: ProTeamIndex,
        previous: ObservationRun?,
        newTransactions: List<Transaction>,
        playerNames: Map<Int, String>,
        week: Int
    ): List<ResearchFlag> {
        val flags = mutableListOf<ResearchFlag>()
        val myTeamId = league.myTeamId

        // Every rostered player in the league, paired with who owns them.
        val rostered: List<Pair<FantasyTeam, RosterPlayer>> =
            league.teams.flatMap { t -> t.roster.map { t to it } }

        // ---- TEAM CHANGE — the highest-value flag ------------------------
        if (previous != null) {
            val current = buildMap<Int, Pair<Int, String>> {
                wire.forEach { put(it.playerId, it.proTeamId to it.name) }
                rostered.forEach { (_, p) -> put(p.playerId, p.proTeamId to p.name) }
            }
            current.forEach { (id, pair) ->
                val (nowTeam, name) = pair
                val was = previous.players[id] ?: return@forEach
                if (was.proTeamId != nowTeam && nowTeam != 0 && was.proTeamId != 0) {
                    val owner = rostered.firstOrNull { it.second.playerId == id }?.first
                    val ownerNote = when {
                        owner == null -> "free agent"
                        owner.id == myTeamId -> "MINE"
                        else -> "rostered by ${owner.name}"
                    }
                    flags.add(
                        ResearchFlag(
                            FlagType.TEAM_CHANGE, id,
                            "$name ${pro.abbrev(was.proTeamId)} -> ${pro.abbrev(nowTeam)} " +
                                "($ownerNote) — traded or signed, check role and depth chart",
                            weight = 0.0
                        )
                    )
                }
            }
        }

        // ---- MY ROSTER INJURIES ------------------------------------------
        league.myTeam?.roster
            ?.filter { it.positionId != 16 }
            ?.filterNot { it.healthy }?.forEach { p ->
                flags.add(
                    ResearchFlag(
                        FlagType.MY_INJURY, p.playerId,
                        "${p.name} (${p.position}, ${pro.abbrev(p.proTeamId)}) is " +
                            "${p.injuryStatus} — practice reports, expected snaps, handcuff",
                        weight = -(p.projection ?: 0.0)
                    )
                )
            }

        // ---- TRADE TARGETS ------------------------------------------------
        // When a rostered player is genuinely unavailable, whoever inherits
        // his touches is often sitting on ANOTHER manager's bench carrying a
        // stale ESPN projection. That is a better opportunity than any waiver
        // claim, because the wire is visible to all ten managers and a bench
        // player is only visible to one — who hasn't repriced him yet.
        val unavailable = rostered.filter { !isAvailable(it.second) }
        val rivalBench = rostered
            .filter { it.first.id != myTeamId }
            .filter { !it.second.isStarter }

        unavailable
            .sortedByDescending { it.second.projection ?: 0.0 }
            .forEach { (outTeam, out) ->
                rivalBench
                    .filter { (_, b) -> b.proTeamId == out.proTeamId }
                    .filter { (_, b) -> b.positionId == out.positionId }
                    .filter { (_, b) -> b.playerId != out.playerId }
                    .filter { (_, b) -> b.healthy }
                    .forEach { (ownerTeam, beneficiary) ->
                        val proj = beneficiary.projection ?: 0.0
                        val was = previous?.players?.get(beneficiary.playerId)?.projection
                        // The buy-low window closes when ESPN's projection
                        // catches up. Fire on the projection delta, not on
                        // an injury somewhere else on the depth chart.
                        val repriced = was != null && was > 0.0 && proj > was * 1.5
                        val urgency = when {
                            repriced -> " ESPN already moved him ${fmt(was!!)} -> " +
                                "${fmt(proj)} — the buy-low window has CLOSED, " +
                                "price him as a starter."
                            proj < (out.projection ?: 0.0) * 0.5 ->
                                " ESPN still projects ${fmt(proj)} — that number " +
                                "predates the news. Offer before they reprice him."
                            // With no baseline we cannot know whether the
                            // window is still open. Say so rather than assert it.
                            previous == null ->
                                " No price history yet — check ESPN's current " +
                                "projection before assuming a discount."
                            else -> " Offer before they reprice him."
                        }
                        val outStatus = if ((out.projection ?: 0.0) == 0.0)
                            "unavailable" else out.injuryStatus ?: "out"
                        flags.add(
                            ResearchFlag(
                                FlagType.TRADE_TARGET, beneficiary.playerId,
                                "${beneficiary.name} (${beneficiary.position}, " +
                                    "${pro.abbrev(beneficiary.proTeamId)}) is on " +
                                    "${ownerTeam.name}'s BENCH. ${out.name} " +
                                    "(${outTeam.name}) is $outStatus.$urgency",
                                // Rank by how much production was vacated.
                                weight = -(out.projection ?: 0.0)
                            )
                        )
                    }
            }

        // ---- OWNERSHIP SPIKE vs snapshot ---------------------------------
        if (previous != null) {
            wire.forEach { p ->
                val was = previous.players[p.playerId] ?: return@forEach
                val rise = p.percentOwned - was.percentOwned
                if (rise >= OWNERSHIP_SPIKE_POINTS) {
                    flags.add(
                        ResearchFlag(
                            FlagType.OWNERSHIP_SPIKE, p.playerId,
                            "${p.name} ${fmt(was.percentOwned)}% -> " +
                                "${fmt(p.percentOwned)}% since last run " +
                                "(+${fmt(rise)} over ${windowLabel(previous)})",
                            weight = -rise
                        )
                    )
                }
            }
        }

        // ---- WIRE RISER — the money signal -------------------------------
        val alreadyFlagged = flags.map { it.playerId }.toSet()
        wire.asSequence()
            .filter { it.playerId !in alreadyFlagged }
            .filter { it.percentChange >= WIRE_RISER_DELTA }
            .filter { it.percentOwned < WIRE_RISER_MAX_OWNED }
            .sortedByDescending { it.percentChange }
            .take(12)
            .forEach { p ->
                val projNote = if ((p.projection ?: 0.0) < 3.0)
                    " — ESPN projects ${fmt(p.projection ?: 0.0)}, verify against news"
                else ""
                flags.add(
                    ResearchFlag(
                        FlagType.WIRE_RISER, p.playerId,
                        "${p.name} (${p.position}, ${pro.abbrev(p.proTeamId)}) " +
                            "${fmt(p.percentOwned)}% owned, +${fmt(p.percentChange)}" +
                            projNote,
                        weight = -p.percentChange
                    )
                )
            }

        // ---- RIVAL TRANSACTIONS, from ESPN's activity feed ---------------
        // Sourced from events, never from a roster diff: the feed is
        // authoritative, permanent, and survives any number of refreshes.
        newTransactions
            .filter { it.teamId != myTeamId }
            .filter { it.kind != TxKind.LINEUP && it.kind != TxKind.UNKNOWN }
            .take(10)
            .forEach { tx ->
                val teamName = league.teams.firstOrNull { it.id == tx.teamId }?.name
                    ?: "team ${tx.teamId}"
                val who = playerNames[tx.playerId]
                    ?: if (ActivityLog.isDefense(tx.playerId))
                        Enums.proTeam(ActivityLog.defenseProTeamId(tx.playerId)) + " D/ST"
                    else "player ${tx.playerId}"
                val verb = when (tx.kind) {
                    TxKind.DROP -> "dropped"
                    TxKind.TRADE -> "traded for"
                    TxKind.WAIVER_ADD -> "won on waivers"
                    else -> "added"
                }
                val tail = if (tx.kind == TxKind.DROP)
                    " — worth claiming, or did they see something?"
                else " — what do they know?"
                flags.add(
                    ResearchFlag(
                        FlagType.ROSTER_MOVE, tx.playerId,
                        "$teamName $verb $who$tail",
                        weight = -tx.whenMillis.toDouble()
                    )
                )
            }

        // ---- RIVAL INJURIES        // ---- RIVAL INJURIES ----------------------------------------------
        // Only genuinely unavailable players — in preseason ESPN marks half
        // the league QUESTIONABLE and it drowns everything that matters.
        league.teams.filter { it.id != myTeamId }.forEach { team ->
            team.roster
                .filter { it.isStarter }
                .filter { it.positionId != 5 && it.positionId != 16 }
                .filterNot { isAvailable(it) }
                .forEach { p ->
                    val beneficiary = wire
                        .filter { it.proTeamId == p.proTeamId }
                        .filter { it.positionId == p.positionId }
                        .filter { it.percentChange > 0.5 }
                        .maxByOrNull { it.percentChange }

                    val tail = if (beneficiary != null) {
                        " — ${beneficiary.name} is on the wire at " +
                            "${fmt(beneficiary.percentOwned)}% owned, " +
                            "+${fmt(beneficiary.percentChange)}. Claim before they do."
                    } else {
                        " — check who absorbs the touches"
                    }

                    flags.add(
                        ResearchFlag(
                            FlagType.OTHER_INJURY, p.playerId,
                            "${team.name}: ${p.name} (${p.position}) ${p.injuryStatus}$tail",
                            weight = -(beneficiary?.percentChange?.times(10)
                                ?: (p.projection ?: 0.0)),
                            // A named claimable beneficiary makes this
                            // actionable today, not background reading.
                            priority = if (beneficiary != null)
                                FlagType.TRADE_TARGET.rank else FlagType.OTHER_INJURY.rank
                        )
                    )
                }
        }

        // ---- MY STARTERS — pure noise before week 2 -----------------------
        if (week >= MY_STARTER_FROM_WEEK) {
            league.myTeam?.roster?.filter { it.isStarter && it.healthy }?.forEach { p ->
                flags.add(
                    ResearchFlag(
                        FlagType.MY_STARTER, p.playerId,
                        "${p.name} (${p.position}) vs " +
                            "${pro.opponent(p.proTeamId, week) ?: "?"} — role and matchup",
                        weight = -(p.projection ?: 0.0)
                    )
                )
            }
        }

        val tradeTargets = flags.filter { it.type == FlagType.TRADE_TARGET }
            .sortedBy { it.weight }
            .take(MAX_TRADE_TARGETS)
            .map { it.playerId }
            .toSet()

        return flags
            .filter { it.type != FlagType.TRADE_TARGET || it.playerId in tradeTargets }
            .distinctBy { it.type to it.playerId }
            .sortedWith(compareBy({ it.priority }, { it.weight }))
            .take(CAP)
    }

    /** OUT, IR, DOUBTFUL, suspensions and exempt list are real unavailability. */
    private fun isAvailable(p: RosterPlayer): Boolean {
        // A player with a zero projection and no real designation is on
        // the exempt list or similar. ESPN's injuryStatus churns for these
        // with no news behind it, so treat them as a settled fact rather
        // than a changing one.
        if ((p.projection ?: 0.0) == 0.0 && p.positionId != 16) return false
        val s = p.injuryStatus?.uppercase() ?: return true
        return !(s.contains("OUT") || s.contains("INJURY_RESERVE") ||
            s.contains("DOUBTFUL") || s.contains("SUSPENSION") ||
            s.contains("EXEMPT") || s.contains("NON_FOOTBALL"))
    }

    /** Everything worth remembering from this run. Append, never overwrite. */
    fun observationOf(league: League, wire: List<WirePlayer>): Map<Int, Observation> =
        buildMap {
            wire.forEach {
                put(it.playerId, Observation(
                    it.percentOwned, it.projection ?: 0.0, it.injuryStatus, it.proTeamId))
            }
            league.teams.forEach { team ->
                team.roster.forEach {
                    put(it.playerId, Observation(
                        it.percentOwned ?: 0.0, it.projection ?: 0.0,
                        it.injuryStatus, it.proTeamId))
                }
            }
        }

    private fun windowLabel(run: ObservationRun): String =
        if (run.ageHours < 1) "${run.ageMinutes}m" else "${run.ageHours}h"

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
