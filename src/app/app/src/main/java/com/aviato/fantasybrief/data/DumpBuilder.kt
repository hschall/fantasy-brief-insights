package com.aviato.fantasybrief.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the dense text block for pasting into Claude.
 *
 * Built for an LLM, not for a human. Density is the feature — the entire
 * league state has to survive one paste. Do not prettify this.
 */
object DumpBuilder {

    fun build(brief: Brief): String {
        val league = brief.league
        val s = league.settings
        val pro = brief.proTeams
        val week = s.scoringPeriodId
        val sb = StringBuilder()

        // ---- header. Generated from mSettings, never hardcoded, because an
        // LLM reading this needs the actual roster rules to advise correctly.
        sb.append("LEAGUE DUMP — ").append(s.name)
            .append(" — week ").append(s.currentMatchupPeriod)
            .append(", scoring period ").append(week).append("\n")
        sb.append(s.size).append(" teams, ").append(s.scoringLabel)
        s.receptionPoints?.let { sb.append(" (").append(it).append(" pt/rec)") }
        sb.append(". Lineup: ").append(s.lineupString).append("\n")
        sb.append("My team: ").append(league.myTeam?.name ?: "?")
            .append(" (id ").append(league.myTeamId ?: -1).append(")\n")
        sb.append("Waivers: ").append(s.waiverSummary).append("\n")
        sb.append("Trade deadline: ")
            .append(if (s.tradeDeadlineMillis > 0)
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(s.tradeDeadlineMillis))
            else "none")
            .append(if (s.tradeReviewHours == 0) ", no review"
                    else ", ${s.tradeReviewHours}h review").append("\n")
        sb.append("Generated: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        brief.previousSnapshotAgeHours?.let {
            sb.append(" | diffed against snapshot ").append(it).append("h old")
        }
        sb.append("\n")

        brief.notes.forEach { sb.append("NOTE: ").append(it).append("\n") }

        // ---- this week's matchups
        if (brief.matchups.isNotEmpty()) {
            sb.append("\n== WEEK ").append(s.currentMatchupPeriod)
                .append(" MATCHUPS ==\n")
            val names = league.teams.associate { it.id to it.name }
            brief.matchups.forEach { m ->
                val mine = m.homeTeamId == league.myTeamId ||
                    m.awayTeamId == league.myTeamId
                sb.append(names[m.awayTeamId] ?: "team ${m.awayTeamId}")
                    .append(" at ").append(names[m.homeTeamId] ?: "team ${m.homeTeamId}")
                if (m.homePoints > 0 || m.awayPoints > 0) {
                    sb.append("  ").append(fmt(m.awayPoints))
                        .append(" - ").append(fmt(m.homePoints))
                }
                if (mine) sb.append("   <== MINE")
                sb.append("\n")
            }
        }

        // ---- my pending claims
        sb.append("\n== PENDING CLAIMS ==\n")
        if (brief.pending.isEmpty()) {
            sb.append("(none in flight)\n")
        } else {
            brief.pending.forEach { claim ->
                sb.append(playerLabel(claim.playerId, brief.playerNames))
                    .append(" — ").append(claim.transactionIds.size)
                    .append(" pending transaction(s)\n")
            }
        }

        // ---- teams
        sb.append("\n== TEAMS ==\n")
        sb.append("id | name | rec | projRank | draftRank | move | waiver | ")
            .append("added | drops | trades\n")
        league.teams.sortedBy { it.id }.forEach { t ->
            // adds derived from roster state; transactionCounter.acquisitions
            // reads 0 for every team even after confirmed claims.
            val added = t.roster.count { !it.wasDrafted }
            sb.append(t.id).append(" | ").append(t.name)
            if (t.id == league.myTeamId) sb.append(" <== ME")
            sb.append(" | ").append(t.record)
                .append(" | ").append(t.projectedRank)
                .append(" | ").append(t.draftDayRank)
                .append(" | ").append(
                    if (t.rankDelta > 0) "+" + t.rankDelta else t.rankDelta.toString())
                .append(" | ").append(t.waiverRank)
                .append(" | ").append(added)
                .append(" | ").append(t.drops)
                .append(" | ").append(t.trades).append("\n")
        }

        // ---- rosters
        sb.append("\n== STANDINGS (rank | team | rec | PF | PWR | 6H) ==\n")
        brief.standings.forEach { r ->
            sb.append(r.rank).append(" | ").append(r.team.name)
            if (r.team.id == league.myTeamId) sb.append(" (ME)")
            sb.append(" | ").append(r.record)
                .append(" | ").append(fmt(r.pointsFor))
                .append(" | ").append(r.power?.let { fmt(it) } ?: "-")
                .append(" | ").append(r.powerDelta?.let {
                    (if (it >= 0) "+" else "") + fmt(it) } ?: "-")
                .append("\n")
        }
        sb.append("weeks of power data: ")
            .append(brief.powerSeries.values.maxOfOrNull { it.points.size } ?: 0)
            .append(", chartable: ").append(brief.powerChartable).append("\n")

        sb.append("\n== ROSTER SHAPE — surplus and holes ==\n")
        brief.shapes.forEach { shape ->
            if (shape.surplus.isEmpty() && shape.holes.isEmpty()) return@forEach
            sb.append(shape.team.name)
            if (shape.team.id == league.myTeamId) sb.append(" (ME)")
            sb.append(" | starting ").append(fmt(shape.startingTotal)).append("\n")
            shape.surplus.forEach { sb.append("   HAS: ").append(it).append("\n") }
            shape.holes.forEach { sb.append("   NEEDS: ").append(it).append("\n") }
        }

        sb.append("\n== ROSTERS ==\n")
        league.teams.sortedBy { it.id }.forEach { t ->
            sb.append("\n-- ").append(t.name).append(" (id ").append(t.id).append(")")
            if (t.id == league.myTeamId) sb.append(" <== MY TEAM")
            sb.append("\n")

            t.roster.forEach { p ->
                sb.append("  ").append(p.slot.padEnd(5))
                    .append(p.position.padEnd(4))
                    .append(p.name)
                    .append(" (").append(pro.abbrev(p.proTeamId)).append(")")
                pro.opponent(p.proTeamId, week)?.let { sb.append(" ").append(it) }
                pro.byeWeek(p.proTeamId)?.let { sb.append(" bye").append(it) }
                sb.append(" proj ")
                    .append(p.projection?.let { fmt(it) } ?: "-")
                // Results beat projections from week 3. Show both so the
                // disagreement is visible rather than requiring arithmetic.
                p.pointsPerGame?.let {
                    sb.append(" actual ").append(fmt(it)).append("/gm")
                        .append(" (").append(p.gamesPlayed).append("g)")
                    val gap = p.projectionGap ?: 0.0
                    if (kotlin.math.abs(gap) >= 3.0) {
                        sb.append(if (gap > 0) " PROJ-HIGH" else " PROJ-LOW")
                    }
                }
                p.percentOwned?.let { sb.append(" own ").append(fmt(it)).append("%") }
                brief.depth.label(p.proTeamId, p.playerId)?.let {
                    sb.append(" ").append(it)
                }
                if (!p.healthy) {
                    // A 0.0 projection with a churning designation means the
                    // exempt list or similar — ESPN has no valid state to
                    // report, so don't repeat the noise as if it were news.
                    sb.append(
                        if ((p.projection ?: 0.0) == 0.0 && p.positionId != 16)
                            " !UNAVAILABLE (no valid ESPN status — verify)"
                        else " !" + p.injuryStatus
                    )
                }
                sb.append("\n")
            }

            // Count ALL rostered players, not just today's starters — a
            // lineup shuffle must not be able to silence a real bye problem.
            val starters = t.roster.filter { it.isStarter }
            (week..17).forEach { w ->
                val outAll = t.roster.filter { pro.isOnBye(it.proTeamId, w) }
                val outStarters = starters.count { pro.isOnBye(it.proTeamId, w) }
                if (outAll.size >= 3) {
                    sb.append("  BYE RISK: wk").append(w).append(": ")
                        .append(outAll.size).append(" rostered / ")
                        .append(outStarters).append(" starters — ")
                        .append(outAll.joinToString(", ") { it.name })
                        .append("\n")
                }
            }
        }

        // ---- what "startable" means in THIS league
        sb.append("\n== REPLACEMENT LEVEL (from the 10 starting lineups) ==\n")
        sb.append("pos | median starter (ELITE) | 25th pct starter (SOLID) | worst | n\n")
        brief.replacement.positions.forEach { lv ->
            sb.append(lv.position).append(" | ").append(fmt(lv.eliteLine))
                .append(" | ").append(fmt(lv.solidLine))
                .append(" | ").append(fmt(lv.worstStarter))
                .append(" | ").append(lv.sampleSize).append("\n")
        }

        // ---- every add is also a drop
        sb.append("\n== DROP CANDIDATES (presentation order — NOT a ranking) ==\n")
        sb.append("Sorted ascending by projection for scanning only. Projection is a\n")
        sb.append("tiebreaker, not a driver. Rank these yourself on path to volume\n")
        sb.append("first, then ownership, then offensive environment.\n")
        sb.append("name | pos | team | proj | own% | delta | bye | inj | flags\n")
        if (brief.dropCandidates.isEmpty()) {
            sb.append("(none — every rostered player is above his position's median starter)\n")
        } else {
            brief.dropCandidates.forEach { c ->
                val p = c.player
                sb.append(p.name).append(" | ").append(p.position)
                    .append(" | ").append(pro.abbrev(p.proTeamId))
                    .append(" | ").append(p.projection?.let { fmt(it) } ?: "-")
                    .append(" | ").append(p.percentOwned?.let { fmt(it) } ?: "?")
                    .append(" | ").append(p.percentChange?.let {
                        (if (it >= 0) "+" else "") + fmt(it) } ?: "?")
                    .append(" | ").append(pro.byeWeek(p.proTeamId) ?: "-")
                    .append(" | ").append(if (p.healthy) "-" else p.injuryTag)
                    .append(" | ").append(
                        if (c.flags.isEmpty()) "-" else c.flags.joinToString(","))
                    .append("\n")
            }
        }

        // ---- who inherits the work, from NFL depth charts
        sb.append("\n== BENEFICIARIES (mechanical, from NFL depth charts) ==\n")
        sb.append("For every unavailable player, who is ranked directly behind him.\n")
        sb.append("Names candidates; does not pick between them. Search to confirm\n")
        sb.append("which one the coaching staff actually favours.\n")
        if (brief.beneficiaries.isEmpty()) {
            sb.append("(none — nobody unavailable has a mapped backup)\n")
        } else {
            brief.beneficiaries.take(12).forEach { b ->
                sb.append(b.name).append(" | ").append(b.position)
                    .append(" ").append(pro.abbrev(b.proTeamId))
                    .append(" depth rank ").append(b.depthRank)
                    .append(" | proj ").append(b.projection?.let { v -> fmt(v) } ?: "-")
                    .append(" | ").append(fmt(b.percentOwned)).append("% owned ")
                    .append(if (b.percentChange >= 0) "+" else "")
                    .append(fmt(b.percentChange))
                    .append(" | ").append(b.availability).append("\n")
                sb.append("   behind ").append(b.blockedBy)
                b.blockerTeamName?.let { n -> sb.append(" (").append(n).append(")") }
                if (b.projectionLooksStale) {
                    sb.append(" — PROJECTION LOOKS STALE, verify against news")
                }
                sb.append("\n")
                brief.trends[b.playerId]?.render()?.takeIf { t -> t.isNotEmpty() }
                    ?.let { t -> sb.append("   ownership: ").append(t).append("\n") }
            }
        }

        // ---- wire, ranked against THIS league's starting lineups
        sb.append("\n== WIRE — TIERED ==\n")
        sb.append("A player appears in exactly one tier. An empty ELITE tier is\n")
        sb.append("normal and more informative than a padded one.\n")
        if (brief.tiers.isEmpty()) {
            sb.append("(nothing available clears any tier)\n")
        } else {
            brief.tiers.forEach { (tier, players) ->
                sb.append("\n-- ").append(tier.label).append("\n")
                players.forEach { t ->
                    val p = t.player
                    sb.append("  ").append(p.name).append(" | ").append(p.position)
                        .append(" | ").append(pro.abbrev(p.proTeamId))
                        .append(" | ").append(pro.opponent(p.proTeamId, week) ?: "-")
                        .append(" | bye ").append(pro.byeWeek(p.proTeamId) ?: "-")
                        .append(" | ").append(fmt(p.percentOwned)).append("% owned")
                        .append(" | ").append(if (p.percentChange >= 0) "+" else "")
                        .append(fmt(p.percentChange))
                        .append(" | proj ").append(p.projection?.let { fmt(it) } ?: "-")
                    if (t.flags.isNotEmpty()) {
                        sb.append(" | ").append(t.flags.joinToString(","))
                    }
                    t.note?.let { sb.append("\n      ").append(it) }
                    sb.append("\n")
                }
            }
        }

        // ---- transaction history, from ESPN's permanent feed
        sb.append("\n== LEAGUE ACTIVITY (last 14 days, from ESPN activity log) ==\n")
        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        val teamName = league.teams.associate { it.id to it.name }
        val ownerOf = league.teams
            .flatMap { t -> t.roster.map { it.playerId to t.id } }.toMap()
        val recent = brief.transactions
            .filter { it.whenMillis >= cutoff && it.kind != TxKind.LINEUP }
        if (recent.isEmpty()) {
            sb.append("(none)\n")
        } else {
            recent.take(40).forEach { tx ->
                sb.append(stamp(tx.whenMillis)).append(" | ")
                    .append(teamName[tx.teamId] ?: "team ${tx.teamId}")
                    .append(if (tx.teamId == league.myTeamId) " (ME)" else "")
                    .append(" | ").append(tx.kind.name)
                    .append(" | ").append(playerLabel(tx.playerId, brief.playerNames))
                tx.counterpartyTeamId?.let {
                    sb.append(" | with ").append(teamName[it] ?: "team $it")
                }
                if (tx.kind == TxKind.TRADE) {
                    // ESPN logs the OFFER. Only the roster says whether it took.
                    when (ActivityLog.tradeState(tx, ownerOf)) {
                        TradeState.COMPLETED -> sb.append(" | COMPLETED")
                        TradeState.NOT_COMPLETED ->
                            sb.append(" | OFFER NOT ACCEPTED (player never moved)")
                        TradeState.UNKNOWN -> sb.append(" | outcome unknown")
                    }
                }
                sb.append("\n")
            }
        }

        sb.append("\n== AT RISK — starters who may not play, backup unowned ==\n")
        if (brief.atRisk.isEmpty()) {
            sb.append("(none)\n")
        } else {
            brief.atRisk.forEach { r ->
                sb.append(if (r.isMine) "MINE | " else "${r.ownerName} | ")
                    .append(r.starter.name).append(" (").append(r.starter.position)
                    .append("1 ").append(pro.abbrev(r.proTeamId)).append(") ")
                    .append(r.designation).append(" — ").append(r.certaintyLabel)
                    .append(", ").append(fmt(r.vacating)).append(" projected\n")
                sb.append("   backup: ").append(r.backup.name).append(" ")
                    .append(fmt(r.backup.percentOwned)).append("% owned")
                    .append(if (r.onWaivers) " — ON WAIVERS" else " — FREE AGENT")
                    .append("\n")
            }
        }

        // ---- the wire WITHOUT a relevance filter
        //
        // Every other wire section here is ranked against my lineup, so a
        // player spiking for a reason the app cannot see never appears — and
        // that is exactly the one worth researching. Ownership moves days
        // before projections do.
        sb.append("\n== MOVING — available, regardless of whether they help me ==\n")
        val climbing = brief.pool
            .filter { it.percentChange >= 0.5 }
            .filter { it.positionId != 5 && it.positionId != 16 }
            .sortedByDescending { it.percentChange }
            .take(12)
        if (climbing.isEmpty()) {
            sb.append("(nothing moving)\n")
        } else {
            climbing.forEach { p ->
                sb.append(p.name).append(" | ").append(p.position)
                    .append(" ").append(pro.abbrev(p.proTeamId))
                    .append(" | ").append(fmt(p.percentOwned)).append("% owned ")
                    .append(if (p.percentChange >= 0) "+" else "")
                    .append(fmt(p.percentChange))
                    .append(" | proj ").append(p.projection?.let { fmt(it) } ?: "-")
                brief.depth.label(p.proTeamId, p.playerId)?.let {
                    sb.append(" | ").append(it)
                }
                if (p.status == "WAIVERS") sb.append(" | WAIVERS")
                sb.append("\n")
            }
        }

        val cutoff48 = System.currentTimeMillis() - 48L * 3_600_000
        val availableIds = brief.pool.map { it.playerId }.toSet()
        val recentlyDropped = brief.transactions
            .filter { it.kind == TxKind.DROP && it.whenMillis >= cutoff48 }
            .filter { it.playerId in availableIds }
            .distinctBy { it.playerId }
        if (recentlyDropped.isNotEmpty()) {
            sb.append("\nDropped in the last 48h and still unowned:\n")
            val teamNames = league.teams.associate { it.id to it.name }
            recentlyDropped.take(8).forEach { tx ->
                val p = brief.pool.firstOrNull { it.playerId == tx.playerId }
                val hrs = (System.currentTimeMillis() - tx.whenMillis) / 3_600_000
                sb.append("  ").append(p?.name ?: brief.playerNames[tx.playerId] ?: "?")
                p?.let {
                    sb.append(" (").append(it.position).append(" ")
                        .append(pro.abbrev(it.proTeamId)).append(", proj ")
                        .append(fmt(it.projection ?: 0.0)).append(")")
                }
                sb.append(" — dropped by ").append(teamNames[tx.teamId] ?: "?")
                    .append(" ").append(hrs).append("h ago\n")
            }
        }

        // The earliest signal there is: it precedes both the ownership spike
        // and the projection catching up.
        if (brief.depthMoves.isNotEmpty()) {
            sb.append("\nDepth chart moves, last 72h:\n")
            brief.depthMoves.take(10).forEach { m ->
                val name = brief.playerNames[m.playerId]
                    ?: brief.pool.firstOrNull { it.playerId == m.playerId }?.name
                    ?: return@forEach
                val owned = league.teams.any { t ->
                    t.roster.any { it.playerId == m.playerId }
                }
                sb.append("  ").append(name).append(" ")
                    .append(pro.abbrev(m.proTeamId)).append(" ")
                    .append(m.slot.uppercase()).append(m.fromRank)
                    .append(" -> ").append(m.slot.uppercase()).append(m.toRank)
                    .append(if (owned) " (rostered)" else " (AVAILABLE)")
                    .append("\n")
            }
        }

        // ---- research queue
        sb.append("\n== RESEARCH QUEUE — SEARCH EACH OF THESE BEFORE ANALYZING ==\n")
        if (brief.flags.isEmpty()) {
            sb.append("(empty — no diffable baseline yet, or nothing changed)\n")
        } else {
            brief.flags.forEachIndexed { i, flag ->
                sb.append(String.format(Locale.US, "%2d. ", i + 1))
                    .append("[").append(flag.type.label).append("] ")
                    .append(flag.text).append("\n")
            }
        }

        val chars = sb.length
        sb.append("\n-- dump size: ").append(chars).append(" chars, ~")
            .append(chars / 4).append(" tokens --\n")

        return sb.toString()
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(millis))

    /** Defenses are encoded as negative ids; show them as such. */
    private fun playerLabel(id: Int, names: Map<Int, String>): String = when {
        names[id] != null -> names[id]!!
        ActivityLog.isDefense(id) ->
            Enums.proTeam(ActivityLog.defenseProTeamId(id)) + " D/ST"
        else -> "player $id"
    }

    private fun wireLine(p: WirePlayer, pro: ProTeamIndex, week: Int): String =
        buildString {
            append(p.name).append(" | ").append(p.position)
                .append(" | ").append(pro.abbrev(p.proTeamId))
                .append(" | ").append(pro.opponent(p.proTeamId, week) ?: "-")
                .append(" | ").append(pro.byeWeek(p.proTeamId) ?: "-")
                .append(" | ").append(fmt(p.percentOwned))
                .append(" | ").append(if (p.percentChange >= 0) "+" else "")
                .append(fmt(p.percentChange))
                .append(" | ").append(p.projection?.let { fmt(it) } ?: "-")
            if (!p.healthy) append(" | !").append(p.injuryStatus)
            if (p.status == "WAIVERS") append(" | WAIVERS")
            append("\n")
        }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
