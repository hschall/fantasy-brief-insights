package com.aviato.fantasybrief.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two dumps, two jobs.
 *
 * DAILY answers "what changed and what do I do about it" — small enough to
 * paste without thinking, and carrying only things where a web search changes
 * the answer.
 *
 * WEEKLY answers "how did that go and what is coming" — run once after
 * waivers process.
 *
 * Both deliberately EXCLUDE what the app already computes and displays: tiers,
 * replacement level tables, power rankings, roster shape. Those are derived
 * from projections and news does not move them, so shipping them to an LLM
 * spends tokens on arithmetic it should not redo.
 */
object DumpBuilder2 {

    fun daily(brief: Brief): String {
        val sb = StringBuilder()
        val league = brief.league
        val s = league.settings
        val pro = brief.proTeams
        val week = s.scoringPeriodId

        header(sb, brief, "DAILY")
        contract(sb)
        sb.append("\nWhat I need from you: live news on the players below. The app\n")
        sb.append("already knows projections, depth ranks, ownership and tiers — it\n")
        sb.append("cannot know whether a designation is real, whether a role change\n")
        sb.append("stuck, or why ownership is moving. That is the gap.\n")

        atRisk(sb, brief)
        myLineup(sb, brief, week)
        wire(sb, brief, week, deep = false)
        movers(sb, brief)
        beneficiaries(sb, brief)
        activity(sb, brief, hours = 24)
        replacementLine(sb, brief)
        researchQueue(sb, brief)
        footer(sb)
        return sb.toString()
    }

    fun weekly(brief: Brief): String {
        val sb = StringBuilder()
        val league = brief.league
        val week = league.settings.scoringPeriodId

        header(sb, brief, "WEEKLY")
        contract(sb)
        sb.append("\nRun after waivers. Backward-looking: how last week went, what is\n")
        sb.append("coming, and who is worth acquiring. Same rule as the daily — the\n")
        sb.append("app owns the arithmetic, you own the news.\n")

        lastWeek(sb, brief, week - 1)
        breakouts(sb, brief, week - 1)
        atRisk(sb, brief)
        thisWeekMatchup(sb, brief, week)
        byeOutlook(sb, brief, week)
        myRoster(sb, brief, week)
        rivalStarters(sb, brief, week)
        wire(sb, brief, week, deep = true)
        movers(sb, brief)
        beneficiaries(sb, brief)
        activity(sb, brief, hours = 24 * 7)
        replacementLine(sb, brief)
        researchQueue(sb, brief)
        footer(sb)
        return sb.toString()
    }


    /**
     * The terms of the exchange, stated in the dump so they travel with the
     * paste rather than living only in a project setting.
     *
     * The app supplies projections, depth ranks, ownership and tiers. Those are
     * inputs. The only thing an LLM adds is the news layer, and a
     * recommendation built on the dump alone is worth nothing — the dump is
     * already readable.
     */
    private fun contract(sb: StringBuilder) {
        sb.append("\n== HOW TO USE THIS ==\n")
        sb.append("NO RECOMMENDATION WITHOUT A SEARCH.\n\n")
        sb.append("If you name a player in any recommendation — start, sit, add,\n")
        sb.append("drop, trade, or \"monitor\" — search him in this response. No\n")
        sb.append("exceptions. Everything below is INPUT, not analysis: projection,\n")
        sb.append("ownership, depth rank and tier are already computed. Your only\n")
        sb.append("value is the news layer.\n\n")
        sb.append("Open your response with:\n")
        sb.append("SEARCHED: name — one-line finding | name — one-line finding | ...\n\n")
        sb.append("Every player you name anywhere must appear in that line. If a\n")
        sb.append("queue item went unsearched, list it under SKIPPED with the\n")
        sb.append("reason. If there is nothing to report on a flagged player, that\n")
        sb.append("IS a finding — say so: \"no reporting found, designation likely\n")
        sb.append("stale.\"\n")
    }

    // ---- shared sections --------------------------------------------------

    private fun header(sb: StringBuilder, b: Brief, kind: String) {
        val s = b.league.settings
        sb.append(kind).append(" DUMP — ").append(s.name)
            .append(" — week ").append(s.currentMatchupPeriod).append("\n")
        sb.append(s.size).append(" teams, ").append(s.scoringLabel)
        s.receptionPoints?.let { sb.append(" (").append(it).append(" pt/rec)") }
        sb.append(". ").append(s.lineupString).append("\n")
        sb.append("My team: ").append(b.league.myTeam?.name ?: "?").append("\n")
        sb.append("Waivers: ").append(s.waiverSummary).append("\n")
        sb.append("Generated ")
            .append(SimpleDateFormat("EEE d MMM HH:mm", Locale.US).format(Date()))
        b.baselineAgeMinutes?.let { sb.append(", diffed against ").append(it).append("m ago") }
        sb.append("\n")
        b.notes.forEach { sb.append("NOTE: ").append(it).append("\n") }
    }

    /** The section that most needs a human with a browser. */
    private fun atRisk(sb: StringBuilder, b: Brief) {
        sb.append("\n== AT RISK — starters who may not play, backup unowned ==\n")
        sb.append("For each: how likely is he actually to play? A designation is a\n")
        sb.append("label, not a probability, and only practice reports settle it.\n")
        if (b.atRisk.isEmpty()) {
            sb.append("(none)\n")
            return
        }
        sb.append(b.atRisk.size).append(" flagged\n")
        b.atRisk.forEach { p ->
            sb.append(if (p.isMine) "MINE | " else "${p.ownerName} | ")
                .append(p.starter.name).append(" (").append(p.starter.position)
                .append("1 ").append(b.proTeams.abbrev(p.proTeamId)).append(") ")
                .append(p.designation).append(" — ").append(p.certaintyLabel)
                .append(", ").append(fmt(p.vacating)).append(" projected\n")
            sb.append("   backup: ").append(p.backup.name)
                .append(" (").append(p.backup.position)
                .append(p.backupDepthRank ?: 2).append(") ")
                .append(fmt(p.backup.percentOwned)).append("% owned, proj ")
                .append(fmt(p.backup.projection ?: 0.0))
                .append(if (p.onWaivers) " — ON WAIVERS" else " — FREE AGENT")
                .append("\n")
        }
    }

    private fun myLineup(sb: StringBuilder, b: Brief, week: Int) {
        val team = b.league.myTeam ?: return
        val pro = b.proTeams
        sb.append("\n== MY LINEUP ==\n")
        sb.append("#N is the playerId. Use it on any item you send back.\n")
        team.roster.forEach { p ->
            sb.append(if (p.isStarter) "  " else "  BN ")
            if (p.isStarter) sb.append(p.slot.padEnd(5))
            sb.append(p.name).append(" #").append(p.playerId)
                .append(" (").append(p.position).append(" ")
                .append(pro.abbrev(p.proTeamId)).append(")")
            pro.opponent(p.proTeamId, week)?.let { sb.append(" ").append(it) }
            sb.append(" proj ").append(p.projection?.let { fmt(it) } ?: "-")
            b.depth.label(p.proTeamId, p.playerId)?.let { sb.append(" ").append(it) }
            p.pointsPerGame?.let { sb.append(" avg ").append(fmt(it)) }
            if (!p.healthy) sb.append(" !").append(p.injuryStatus)
            sb.append("\n")
        }
    }

    private fun myRoster(sb: StringBuilder, b: Brief, week: Int) = myLineup(sb, b, week)

    /**
     * Rival STARTERS only, one line each. Their benches were the single
     * largest cost in the old dump and news about a rival's bench player
     * reaches me through AT RISK and BENEFICIARIES anyway.
     */
    private fun rivalStarters(sb: StringBuilder, b: Brief, week: Int) {
        sb.append("\n== RIVAL STARTERS (for trade context) ==\n")
        b.league.teams.filter { it.id != b.league.myTeamId }.forEach { t ->
            sb.append(t.name).append(": ")
            sb.append(
                t.roster.filter { it.isStarter }
                    .sortedByDescending { it.projection ?: 0.0 }
                    .joinToString(", ") {
                        it.name + " " + fmt(it.projection ?: 0.0) +
                            if (!it.healthy) "!" else ""
                    }
            )
            sb.append("\n")
        }
    }

    private fun wire(sb: StringBuilder, b: Brief, week: Int, deep: Boolean) {
        val tiers = if (deep)
            listOf(Tier.ELITE, Tier.SOLID, Tier.LOTTERY, Tier.HANDCUFF)
        else listOf(Tier.ELITE, Tier.SOLID, Tier.LOTTERY)

        sb.append("\n== WIRE ==\n")
        var any = false
        tiers.forEach { tier ->
            val players = b.tiers[tier].orEmpty()
            if (players.isEmpty()) return@forEach
            any = true
            sb.append("-- ").append(tier.label).append("\n")
            players.take(if (deep) 8 else 5).forEach { t ->
                val p = t.player
                sb.append("  ").append(p.name).append(" | ").append(p.position)
                    .append(" ").append(b.proTeams.abbrev(p.proTeamId))
                    .append(" | ").append(fmt(p.percentOwned)).append("% owned ")
                    .append(if (p.percentChange >= 0) "+" else "")
                    .append(fmt(p.percentChange))
                    .append(" | proj ").append(p.projection?.let { fmt(it) } ?: "-")
                b.depth.label(p.proTeamId, p.playerId)?.let { sb.append(" | ").append(it) }
                if (p.status == "WAIVERS") sb.append(" | WAIVERS")
                sb.append("\n")
                t.note?.let { sb.append("     ").append(it).append("\n") }
            }
        }
        if (!any) sb.append("(nothing clears a tier)\n")
    }

    /** The section the app most clearly cannot judge on its own. */
    private fun breakouts(sb: StringBuilder, b: Brief, week: Int) {
        sb.append("\n== BREAKOUTS — available players who scored last week ==\n")
        sb.append("Was this a role change or one long touchdown? The app cannot\n")
        sb.append("tell. Snap share, targets and what the coach said settle it.\n")
        if (b.breakouts.isEmpty()) {
            sb.append("(none — no completed week, or no available player beat his ")
                .append("projection by enough)\n")
            return
        }
        b.breakouts.take(10).forEach { br ->
            sb.append(br.player.name).append(" | ").append(br.player.position)
                .append(" ").append(b.proTeams.abbrev(br.player.proTeamId))
                .append(" | scored ").append(fmt(br.actual))
                .append(" on a ").append(fmt(br.projected)).append(" projection")
                .append(" (").append(fmt(br.surprise)).append(" over)")
                .append(" | ").append(fmt(br.player.percentOwned)).append("% owned ")
                .append(if (br.ownershipDelta >= 0) "+" else "").append(fmt(br.ownershipDelta))
            br.depthRank?.let { sb.append(" | ").append(br.player.position).append(it) }
            sb.append("\n")
        }
    }

    private fun lastWeek(sb: StringBuilder, b: Brief, week: Int) {
        if (week < 1) return
        val archive = b.weekArchives[week] ?: run {
            sb.append("\n== LAST WEEK ==\n(no archive for week ").append(week)
                .append(")\n")
            return
        }
        val mine = archive.rosters[b.league.myTeamId] ?: return
        sb.append("\n== LAST WEEK (week ").append(week).append(") ==\n")
        sb.append("Scored ").append(fmt(mine.total)).append("\n")
        sb.append("Starters, actual vs projected:\n")
        mine.players.filter { it.isStarter }
            .sortedByDescending { it.actual - it.projection }
            .forEach { p ->
                val gap = p.actual - p.projection
                sb.append("  ").append(p.name).append(" ").append(fmt(p.actual))
                    .append(" vs ").append(fmt(p.projection)).append(" proj (")
                    .append(if (gap >= 0) "+" else "").append(fmt(gap)).append(")\n")
            }
    }

    private fun thisWeekMatchup(sb: StringBuilder, b: Brief, week: Int) {
        val myId = b.league.myTeamId ?: return
        val m = b.matchups.firstOrNull { it.homeTeamId == myId || it.awayTeamId == myId }
            ?: return
        val oppId = if (m.homeTeamId == myId) m.awayTeamId else m.homeTeamId
        val opp = b.league.teams.firstOrNull { it.id == oppId } ?: return
        val mineTotal = b.league.myTeam?.roster?.filter { it.isStarter }
            ?.sumOf { it.projection ?: 0.0 } ?: 0.0
        val theirTotal = opp.roster.filter { it.isStarter }.sumOf { it.projection ?: 0.0 }
        sb.append("\n== THIS WEEK ==\n")
        sb.append("vs ").append(opp.name).append(" — projected ")
            .append(fmt(mineTotal)).append(" to ").append(fmt(theirTotal)).append("\n")
        opp.roster.filter { it.isStarter && !it.healthy }.forEach {
            sb.append("  their ").append(it.position).append(" ").append(it.name)
                .append(" is ").append(it.injuryStatus).append("\n")
        }
    }

    private fun byeOutlook(sb: StringBuilder, b: Brief, week: Int) {
        val team = b.league.myTeam ?: return
        val pro = b.proTeams
        sb.append("\n== BYES, NEXT 4 WEEKS ==\n")
        (week..minOf(week + 3, 17)).forEach { w ->
            val out = team.roster.filter { pro.isOnBye(it.proTeamId, w) }
            val starters = out.filter {
                it.isStarter && it.positionId != 5 && it.positionId != 16
            }
            if (out.isEmpty()) return@forEach
            sb.append("wk").append(w).append(": ").append(starters.size)
                .append(" starter(s), ").append(out.size - starters.size)
                .append(" bench — ").append(out.joinToString(", ") { it.name })
                .append("\n")
        }
    }

    private fun beneficiaries(sb: StringBuilder, b: Brief) {
        if (b.beneficiaries.isEmpty()) return
        sb.append("\n== BENEFICIARIES (from NFL depth charts) ==\n")
        b.beneficiaries.take(8).forEach { x ->
            sb.append(x.name).append(" (").append(x.position).append(" ")
                .append(b.proTeams.abbrev(x.proTeamId)).append(", rank ")
                .append(x.depthRank).append(") behind ").append(x.blockedBy)
            x.blockerTeamName?.let { sb.append(" [").append(it).append("]") }
            sb.append(" — ").append(x.availability)
            if (x.projectionLooksStale) sb.append(" — PROJECTION LOOKS STALE")
            sb.append("\n")
        }
    }

    private fun activity(sb: StringBuilder, b: Brief, hours: Int) {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        val recent = b.transactions
            .filter { it.kind != TxKind.LINEUP && it.kind != TxKind.UNKNOWN }
            .filter { it.whenMillis >= cutoff }
        sb.append("\n== ACTIVITY (last ").append(hours).append("h) ==\n")
        if (recent.isEmpty()) { sb.append("(none)\n"); return }
        val names = b.league.teams.associate { it.id to it.name }
        recent.take(25).forEach { tx ->
            sb.append(names[tx.teamId] ?: "team ${tx.teamId}")
                .append(if (tx.teamId == b.league.myTeamId) " (ME)" else "")
                .append(" ").append(tx.kind.name.lowercase()).append(" ")
                .append(b.playerNames[tx.playerId] ?: "player ${tx.playerId}")
                .append("\n")
        }
    }

    /**
     * The wire WITHOUT a relevance filter.
     *
     * Everything else here is ranked against my lineup, which means a player
     * spiking for a reason the app cannot see never appears — and that is
     * precisely the player worth researching. Ownership moves days before
     * projections do, so movement is the signal and the reason for it is the
     * thing only a search can supply.
     *
     * Deliberately unranked by usefulness to me. Breadth is the point.
     */
    private fun movers(sb: StringBuilder, b: Brief) {
        val pro = b.proTeams
        val week = b.league.settings.scoringPeriodId

        sb.append("\n== MOVING — available, regardless of whether they help me ==\n")
        sb.append("Why is each of these climbing? The app sees the movement and\n")
        sb.append("cannot see the cause.\n")

        val climbing = b.pool
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
                b.depth.label(p.proTeamId, p.playerId)?.let {
                    sb.append(" | ").append(it)
                }
                if (p.status == "WAIVERS") sb.append(" | WAIVERS")
                sb.append("\n")
            }
        }

        // A rival gave up on him. Either they know something or they are wrong,
        // and which one it is lives entirely in the news.
        val cutoff = System.currentTimeMillis() - 48L * 3_600_000
        val availableIds = b.pool.map { it.playerId }.toSet()
        val dropped = b.transactions
            .filter { it.kind == TxKind.DROP && it.whenMillis >= cutoff }
            .filter { it.playerId in availableIds }
            .distinctBy { it.playerId }
        if (dropped.isNotEmpty()) {
            sb.append("\nDropped in the last 48h and still unowned:\n")
            val names = b.league.teams.associate { it.id to it.name }
            dropped.take(8).forEach { tx ->
                val p = b.pool.firstOrNull { it.playerId == tx.playerId }
                val hours = (System.currentTimeMillis() - tx.whenMillis) / 3_600_000
                sb.append("  ").append(p?.name ?: b.playerNames[tx.playerId] ?: "?")
                p?.let {
                    sb.append(" (").append(it.position).append(" ")
                        .append(pro.abbrev(it.proTeamId)).append(", proj ")
                        .append(fmt(it.projection ?: 0.0)).append(")")
                }
                sb.append(" — dropped by ").append(names[tx.teamId] ?: "?")
                    .append(" ").append(hours).append("h ago\n")
            }
        }

        // A depth chart move is the earliest signal there is — it precedes
        // both the ownership spike and the projection catching up.
        if (b.depthMoves.isNotEmpty()) {
            sb.append("\nDepth chart moves, last 72h:\n")
            b.depthMoves.take(10).forEach { m ->
                val name = b.playerNames[m.playerId]
                    ?: b.pool.firstOrNull { it.playerId == m.playerId }?.name
                    ?: return@forEach
                val owned = b.league.teams.any { t ->
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
    }

    /** One line, not a table — enough to judge a number without a lookup. */
    private fun replacementLine(sb: StringBuilder, b: Brief) {
        sb.append("\n== STARTABLE HERE (median starter by position) ==\n")
        sb.append(b.replacement.positions.joinToString("  ") {
            "${it.position} ${fmt(it.eliteLine)}"
        }).append("\n")
    }

    private fun researchQueue(sb: StringBuilder, b: Brief) {
        if (b.flags.isEmpty()) return
        sb.append("\n== SEARCH THESE ==\n")
        b.flags.take(12).forEachIndexed { i, f ->
            sb.append(i + 1).append(". [").append(f.type.label).append("] ")
                .append(f.text).append("\n")
        }
    }

    private fun footer(sb: StringBuilder) {
        val chars = sb.length
        sb.append("\n-- ").append(chars).append(" chars, ~").append(chars / 4)
            .append(" tokens --\n")
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
