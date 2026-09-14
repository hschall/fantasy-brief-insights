package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.BriefTier
import com.aviato.fantasybrief.data.Candidate
import com.aviato.fantasybrief.data.CardPlayer
import com.aviato.fantasybrief.data.DailyBrief
import com.aviato.fantasybrief.data.DoFirstCard
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.MatchupGrade
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import com.aviato.fantasybrief.data.RosterPlayer
import com.aviato.fantasybrief.data.WirePlayer

/**
 * The daily brief. One screen, one scroll, four sections in fixed order.
 *
 * Sections never reorder and never collapse. An empty section still draws its
 * header and says why it is empty — "nothing to do" is an answer, and a
 * section that vanishes when it has nothing to say is indistinguishable from
 * one that failed to load.
 */
@Composable
fun DailyBriefPane(
    brief: Brief,
    daily: DailyBrief?,
    bottomInset: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    onPlayer: (PlayerFocus) -> Unit = {},
    onAcquire: ((WirePlayer) -> Unit)? = null,
    onSwapTo: ((RosterPlayer, RosterPlayer) -> Unit)? = null,
    /** Starred set for this league, and the toggle. Same list as the wire. */
    starred: Set<Int> = emptySet(),
    onStar: ((Int) -> Unit)? = null
) {
    // One clock for the whole screen: two reads a moment apart can disagree
    // about whether a deadline has passed, and a card that sorts as live
    // while its button refuses is worse than either answer alone.
    val now = remember(daily) { System.currentTimeMillis() }

    // Dismissal is per card id and lasts the session. It suppresses the card,
    // not the recommendation — the same situation tomorrow gets a new card.
    val dismissed = remember(daily) { mutableStateListOf<String>() }

    val cards = daily?.doFirstOrdered(now).orEmpty().filterNot { it.id in dismissed }
    val sortByProjection = remember { androidx.compose.runtime.mutableStateOf(false) }
    val posFilter = remember(daily) { androidx.compose.runtime.mutableStateOf("ALL") }
    // One open at a time: two expanded cards is a wall, and the point of the
    // drop-down is to compare one proposal against the collapsed others.
    val openTrade = remember(daily) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().background(Ink.ground),
        contentPadding = PaddingValues(bottom = bottomInset + 28.dp)
    ) {
        item { BriefHeader(daily, brief, now) }

        item {
            BriefSection(
                "DO FIRST",
                if (cards.isEmpty()) null else "${cards.size} before kickoff"
            )
        }
        if (cards.isEmpty()) {
            item { EmptyLine("Nothing needs doing before the next lock.") }
        }
        items2(cards) { c ->
            DoFirst(c, brief, now, onPlayer, onAcquire, onSwapTo) { dismissed.add(c.id) }
        }

        val roster = brief.league.myTeam?.roster.orEmpty()
        val byProj = daily != null && sortByProjection.value
        item {
            BriefSection(
                "ROSTER",
                "${roster.size} players",
                toggle = if (byProj) "BY PROJECTION" else "BY SLOT",
                onToggle = { sortByProjection.value = !sortByProjection.value }
            )
        }
        if (roster.isEmpty()) {
            item { EmptyLine("No roster loaded.") }
        } else if (byProj) {
            // One ranked list, no group headers — the point of this sort is
            // to see the whole roster against itself.
            items2(roster.sortedByDescending { it.projection ?: 0.0 }) { p ->
                RosterRow(p, brief, daily, now, onPlayer)
            }
        } else {
            val starters = roster.filter { it.lineupSlotId !in BENCH_SLOTS }
                .sortedBy { Enums.slotSortKey(it.lineupSlotId) }
            val bench = roster.filter { it.lineupSlotId in BENCH_SLOTS }
                .sortedByDescending { it.projection ?: 0.0 }
            item { ColumnHeads() }
            item { GroupLabel("STARTING") }
            items2(starters) { p -> RosterRow(p, brief, daily, now, onPlayer) }
            item { GroupLabel("BENCH") }
            items2(bench) { p -> RosterRow(p, brief, daily, now, onPlayer) }
        }

        item {
            BriefSection(
                "TRADES",
                daily?.trades?.size?.takeIf { it > 0 }?.let { "$it proposal${if (it == 1) "" else "s"}" }
            )
        }
        val trades = daily?.trades.orEmpty()
        if (trades.isEmpty()) {
            item { EmptyLine("No proposal where both sides gain. None offered.") }
        }
        items2(trades) { t ->
            TradeCard(t, brief, openTrade.value == t.id) {
                openTrade.value = if (openTrade.value == t.id) null else t.id
            }
        }

        item {
            BriefSection(
                "CANDIDATES",
                daily?.candidates?.size?.takeIf { it > 0 }?.let { "$it watching" }
            )
        }
        val cands = daily?.candidates.orEmpty()
        if (cands.isEmpty()) {
            item { EmptyLine("Nothing on the wire worth a second look.") }
        } else {
            val positions = listOf("ALL") + cands.mapNotNull { c ->
                brief.pool.firstOrNull { it.playerId == c.playerId }?.position
            }.distinct().sorted()
            item {
                PositionChips(positions, posFilter.value) { posFilter.value = it }
            }
            val shown = cands.filter { c ->
                posFilter.value == "ALL" ||
                    brief.pool.firstOrNull { it.playerId == c.playerId }?.position ==
                        posFilter.value
            }
            if (shown.isEmpty()) {
                item { EmptyLine("Nothing at that position.") }
            }
            items2(shown) { c ->
                CandidateRow(c, brief, daily, starred, onStar, onPlayer)
            }
        }

        if (daily == null) {
            item { EmptyLine("No brief published yet for this league.") }
        }
    }
}

private val BENCH_SLOTS = setOf(20, 21)

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(
    list: List<T>, crossinline row: @Composable (T) -> Unit
) = items(list.size) { i -> row(list[i]) }

/**
 * 12px condensed, a hairline rule filling the rest, count underneath.
 *
 * Named BriefSection because the ui package already has a SectionHeader with
 * a different shape, and being private does not prevent the clash.
 */
@Composable
private fun BriefSection(
    title: String,
    count: String?,
    toggle: String? = null,
    onToggle: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = inkLabel(12.0, Ink.paper))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
            if (toggle != null && onToggle != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    toggle, style = inkLabel(9.0, Ink.accent),
                    modifier = Modifier.clickable(onClick = onToggle)
                )
            }
        }
        if (count != null) {
            Text(count, style = inkLabel(9.5, Ink.mid), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text, style = inkBody(11.5, Ink.mid),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/**
 * One action card.
 *
 * Tier colour is carried by the border, the 3px rail, the badge and the
 * button — never by body text and never by a number. A number in tier colour
 * reads as a value judgement about the number itself.
 */
@Composable
private fun DoFirst(
    c: DoFirstCard,
    brief: Brief,
    now: Long,
    onPlayer: (PlayerFocus) -> Unit,
    onAcquire: ((WirePlayer) -> Unit)?,
    onSwapTo: ((RosterPlayer, RosterPlayer) -> Unit)?,
    onDismiss: () -> Unit
) {
    val tint = Color(BriefTier.hex(c.tier))
    val isSwap = c.action?.type.equals("SWAP", true)
    val mine = brief.league.myTeam?.roster.orEmpty()

    val swapIn = c.action?.playerId?.let { id -> mine.firstOrNull { it.playerId == id } }
    val swapOut = c.action?.dropPlayerId?.let { id -> mine.firstOrNull { it.playerId == id } }
    val target = c.action?.playerId?.takeIf { !isSwap }
        ?.let { id -> brief.pool.firstOrNull { it.playerId == id } }
    val canAct = !c.isExpired(now) && when {
        isSwap -> swapIn != null && swapOut != null && onSwapTo != null
        else -> target != null && onAcquire != null
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.07f))
            .border(0.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
    ) {
        Row(Modifier.fillMaxWidth()) {
            // The 3px rail, full height of the card.
            Box(
                Modifier.width(3.dp).height(IntrinsicCardHeight)
                    .background(tint, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
            )
            Column(Modifier.weight(1f).padding(11.dp, 12.dp, 12.dp, 12.dp)) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    c.source?.let {
                        Box(
                            Modifier.clip(RoundedCornerShape(5.dp))
                                .border(0.5.dp, Ink.border, RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text(it, style = inkLabel(9.0, Ink.mid)) }
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(c.tier.uppercase(), style = inkLabel(9.0, tint))
                    Spacer(Modifier.weight(1f))
                    c.status?.let {
                        Text(statusLabel(it), style = inkLabel(9.0, Ink.mid))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        "\u00D7", style = inkLabel(13.0, Ink.mid),
                        modifier = Modifier.clickable(onClick = onDismiss)
                            .padding(horizontal = 4.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Both blocks own an avatar, so the two text columns
                    // resolve to the same width and the arrow sits centred.
                    Side(c.outgoing, brief, tint, Modifier.weight(1f), onPlayer)
                    if (c.incoming != null && c.outgoing != null) {
                        Text(
                            "\u2192", style = inkLabel(13.0, Ink.mid),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                    if (c.incoming != null) {
                        Side(c.incoming, brief, tint, Modifier.weight(1f), onPlayer)
                    }
                }

                c.why?.let {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 11.dp).height(1.dp)
                            .background(Ink.border)
                    )
                    Text(
                        it, style = inkBody(11.0, Ink.mid),
                        modifier = Modifier.padding(top = 9.dp)
                    )
                }

                if (c.action != null) {
                    Spacer(Modifier.height(11.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(tint.copy(alpha = 0.12f))
                            .border(0.5.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .then(
                                if (canAct) Modifier.clickable {
                                    if (isSwap) onSwapTo?.invoke(swapIn!!, swapOut!!)
                                    else onAcquire?.invoke(target!!)
                                } else Modifier
                            )
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (canAct) c.action.label.uppercase() else "WINDOW CLOSED",
                            style = inkLabel(10.0, if (canAct) tint else Ink.mid)
                        )
                    }
                }
            }
        }
    }
}

/** Rail height. Compose has no full-height sibling, so this is the floor. */
private val IntrinsicCardHeight = 96.dp

@Composable
private fun Side(
    p: CardPlayer?,
    brief: Brief,
    tint: Color,
    modifier: Modifier,
    onPlayer: (PlayerFocus) -> Unit
) {
    if (p == null) { Spacer(modifier); return }
    val found = brief.league.teams.firstNotNullOfOrNull { t ->
        t.roster.firstOrNull { it.playerId == p.playerId }?.let { t.id to it }
    }
    val wire = brief.pool.firstOrNull { it.playerId == p.playerId }
    val name = found?.second?.name ?: wire?.name ?: "Player ${p.playerId}"
    val pos = found?.second?.position ?: wire?.position ?: ""
    val proTeamId = found?.second?.proTeamId ?: wire?.proTeamId
    val proj = found?.second?.projection ?: wire?.projection
    val rank = brief.rankings[p.playerId]

    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier.clickable(enabled = found != null) {
                found?.let { onPlayer(it.second.focus(it.first)) }
            }
        ) {
            RankedHeadshot(
                p.playerId, name, rank?.badge(), rank?.delta, 36.dp, tint,
                isDst = p.playerId < 0,
                proAbbrev = proTeamId?.let { brief.proTeams.abbrev(it) } ?: ""
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, style = inkBody(12.5, Ink.paper),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(pos.ifBlank { null },
                    proTeamId?.let { brief.proTeams.abbrev(it) }).joinToString(" "),
                style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                listOfNotNull(proj?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    p.role).joinToString(" \u00B7 "),
                style = inkBody(10.5, Ink.mid),
                modifier = Modifier.padding(top = 2.dp)
            )
            // Only ever on the player being replaced.
            p.alert?.let {
                Text(
                    it, style = inkBody(10.5, Ink.negative),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun statusLabel(s: String) = when (s.uppercase()) {
    "FREEAGENT" -> "Free agent"
    "WAIVERS" -> "On waivers"
    else -> "Rostered"
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text, style = inkLabel(9.5, Ink.mid),
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * One roster row: tier rail, headshot with positional rank, name, projection,
 * matchup grade.
 *
 * The grade never overrides the projection. The projection already prices the
 * matchup in — the grade exists to explain why the number is what it is, and
 * to be readable at a glance when the number alone is not.
 */
@Composable
private fun RosterRow(
    p: RosterPlayer,
    brief: Brief,
    daily: DailyBrief?,
    now: Long,
    onPlayer: (PlayerFocus) -> Unit
) {
    val tier = brief.tierOf(p.position, p.projection, p.playerId).tier
    val rail = Color(BriefTier.hex(if (tier == "BELOW") BriefTier.DEPTH else tier))
    val onBye = brief.proTeams.isOnBye(p.proTeamId, brief.league.settings.scoringPeriodId)
    val out = !p.healthy && Enums.injuryShort(p.injuryStatus).let { it == "O" || it == "IR" }
    val sit = out || onBye

    val oppLabel = brief.proTeams.opponent(p.proTeamId, brief.league.settings.scoringPeriodId)
    val oppAbbrev = oppLabel?.removePrefix("@")?.removePrefix("vs ")?.trim()
        ?.takeIf { it.isNotBlank() && it != "BYE" }
    val grade = if (sit) MatchupGrade.SIT
        else daily?.defense?.grade(oppAbbrev, p.position)
    val rank = brief.rankings[p.playerId]

    Row(
        Modifier.fillMaxWidth()
            .clickable { onPlayer(p.focus(brief.league.myTeam?.id ?: 0)) }
            // Tint runs the full width of the row, rail flush to the edge —
            // the wire board's anatomy, so the two read as one app.
            .background(rail.copy(alpha = 0.06f))
            .height(IntrinsicSize.Min)
            // Out and bye rows are context, not decisions.
            .alpha(if (sit) 0.62f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(rail))
        Spacer(Modifier.width(9.dp))
        RankedHeadshot(
            p.playerId, p.name, rank?.badge(), rank?.delta, 38.dp, rail,
            isDst = p.playerId < 0,
            proAbbrev = brief.proTeams.abbrev(p.proTeamId)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Slot sits where the wire board puts its FA/W chip.
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(rail.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) { Text(Enums.slot(p.lineupSlotId), style = inkLabel(8.5, Ink.paper)) }
                Spacer(Modifier.width(7.dp))
                Text(
                    p.name, style = inkBody(14.0, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    p.position + " " + brief.proTeams.abbrev(p.proTeamId),
                    style = inkLabel(8.5, Ink.mid)
                )
                if (!p.healthy) {
                    Spacer(Modifier.width(5.dp))
                    Text(Enums.injuryShort(p.injuryStatus), style = inkLabel(8.5, Ink.negative))
                }
            }
            Text(
                listOfNotNull(
                    p.percentOwned?.let {
                        String.format(java.util.Locale.US, "%.0f%% owned", it)
                    },
                    oppLabel,
                    brief.proTeams.kickoffLabel(
                        p.proTeamId, brief.league.settings.scoringPeriodId
                    )?.uppercase()
                ).joinToString(" \u00B7 "),
                style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        SplitTotal(p.projection, big = 15.0, small = 10.0, color = Ink.paper,
            modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(8.dp))
        GradeTile(grade, sitReason(out, onBye))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x247FB0E0)))
}

private fun sitReason(out: Boolean, onBye: Boolean) =
    when { onBye -> "On bye"; out -> "Ruled out"; else -> null }

/** 68px. Empty when we have no read — absence is not the same as Average. */
@Composable
private fun GradeTile(grade: MatchupGrade?, sub: String?) {
    if (grade == null) { Spacer(Modifier.width(68.dp)); return }
    val c = Color(grade.hex)
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.15f))
            .border(0.5.dp, c.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(grade.label.uppercase(), style = inkLabel(10.5, c))
        if (sub != null) {
            Text(sub, style = inkLabel(8.5, Ink.mid), modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** PLAYER / PROJ / MATCHUP, matching the wire board's heads. */
@Composable
private fun ColumnHeads() {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("PLAYER", style = inkLabel(9.0, Ink.mid), modifier = Modifier.weight(1f))
        Text("PROJ", style = inkLabel(9.0, Ink.mid), modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(8.dp))
        Text("MATCHUP", style = inkLabel(9.0, Ink.mid), modifier = Modifier.width(68.dp))
    }
}

/** Single select. The active chip takes the accent border and a faint fill. */
@Composable
private fun PositionChips(options: List<String>, active: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { o ->
            val on = o == active
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) Ink.accent.copy(alpha = 0.09f) else Color.Transparent)
                    .border(
                        0.5.dp,
                        if (on) Ink.accent else Ink.border,
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { onPick(o) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(o, style = inkLabel(9.0, if (on) Ink.accent else Ink.mid))
            }
        }
    }
}

/**
 * A watch-list row. Wire anatomy, plus the tier rail and the star.
 *
 * The third line is the reason the row exists — usually that a player with a
 * real role is unrostered when he should not be. It is never truncated: a
 * candidate you cannot see the argument for is just a name.
 */
@Composable
private fun CandidateRow(
    c: Candidate,
    brief: Brief,
    daily: DailyBrief?,
    starred: Set<Int>,
    onStar: ((Int) -> Unit)?,
    onPlayer: (PlayerFocus) -> Unit
) {
    val w = brief.pool.firstOrNull { it.playerId == c.playerId }
    val rail = Color(BriefTier.hex(c.tier))
    val rank = brief.rankings[c.playerId]
    val week = brief.league.settings.scoringPeriodId
    val proTeamId = w?.proTeamId
    val oppLabel = proTeamId?.let { brief.proTeams.opponent(it, week) }
    val oppAbbrev = oppLabel?.removePrefix("@")?.removePrefix("vs ")?.trim()
        ?.takeIf { it.isNotBlank() && it != "BYE" }
    val grade = daily?.defense?.grade(oppAbbrev, w?.position)
    val isStar = c.playerId in starred

    Row(
        Modifier.fillMaxWidth()
            .background(rail.copy(alpha = 0.06f))
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(rail))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.clickable(enabled = w != null) {
            w?.let { onPlayer(it.focus()) }
        }) {
            RankedHeadshot(
                c.playerId, w?.name ?: "", rank?.badge(), rank?.delta, 36.dp, rail,
                isDst = c.playerId < 0,
                proAbbrev = proTeamId?.let { brief.proTeams.abbrev(it) } ?: ""
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                w?.status?.let {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Ink.positive.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            if (it == "WAIVERS") "W" else "FA",
                            style = inkLabel(8.5, Ink.positive)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    w?.name ?: "Player ${c.playerId}",
                    style = inkBody(14.0, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(w?.position, proTeamId?.let {
                        brief.proTeams.abbrev(it)
                    }).joinToString(" "),
                    style = inkLabel(8.5, Ink.mid)
                )
                grade?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it.label.uppercase(), style = inkLabel(8.5, Color(it.hex)))
                }
            }
            Text(
                listOfNotNull(
                    w?.percentOwned?.let {
                        String.format(java.util.Locale.US, "%.0f%% owned", it)
                    },
                    oppLabel,
                    proTeamId?.let { brief.proTeams.kickoffLabel(it, week)?.uppercase() }
                ).joinToString(" \u00B7 "),
                style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.padding(top = 3.dp)
            )
            if (c.note.isNotBlank()) {
                Text(
                    c.note, style = inkBody(11.0, Ink.mid),
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
        SplitTotal(w?.projection, big = 15.0, small = 10.0, color = Ink.paper,
            modifier = Modifier.width(40.dp))
        Box(
            Modifier.width(44.dp).fillMaxHeight()
                .clickable(enabled = onStar != null) { onStar?.invoke(c.playerId) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isStar) "\u2605" else "\u2606",
                style = inkLabel(13.0, if (isStar) Ink.positive else Color(0x4D7FB0E0))
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x247FB0E0)))
}

/**
 * A two-way proposal, collapsed to a header and one line of argument.
 *
 * The other manager's gain sits in the headline rather than inside the
 * drop-down. Hiding it is what makes an offer read as lopsided, and an offer
 * that reads as lopsided is declined before it is understood.
 */
@Composable
private fun TradeCard(
    t: com.aviato.fantasybrief.data.TradeProposal,
    brief: Brief,
    open: Boolean,
    onToggle: () -> Unit
) {
    val partner = brief.league.teams.firstOrNull { it.id == t.partnerTeamId }
    val oddsColor = when (t.odds.uppercase()) {
        "LIKELY" -> Ink.positive
        "EVEN" -> Ink.accent
        else -> Ink.mid
    }
    val oddsLabel = when (t.odds.uppercase()) {
        "LIKELY" -> "Likely"
        "EVEN" -> "Even"
        else -> "Long shot"
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.accent.copy(alpha = 0.05f))
            .border(0.5.dp, Ink.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp))
                    .border(0.5.dp, Ink.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    partner?.name?.uppercase() ?: "TEAM ${t.partnerTeamId}",
                    style = inkLabel(9.0, Ink.mid)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(oddsLabel.uppercase(), style = inkLabel(9.0, oddsColor))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (t.yourGain >= 0) "+" else "") +
                        String.format(java.util.Locale.US, "%.1f", t.yourGain),
                    style = inkNum(16.0, Ink.positive)
                )
                Text("TO YOUR WEEK", style = inkLabel(8.0, Ink.mid))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (open) "\u02C5" else "\u203A", style = inkLabel(13.0, Ink.mid))
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            // Dots on the outside edges, so the two lists mirror each other.
            Column(Modifier.weight(1f)) {
                Text("YOU GIVE", style = inkLabel(8.5, Ink.mid))
                t.youGive.forEach { SideLine(it, brief, give = true) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("YOU GET", style = inkLabel(8.5, Ink.mid))
                t.youGet.forEach { SideLine(it, brief, give = false) }
            }
        }

        if (t.headline.isNotBlank()) {
            Text(
                t.headline + "  \u00B7  they gain " +
                    String.format(java.util.Locale.US, "+%.1f", t.theirGain) + " a week",
                style = inkBody(11.0, Ink.mid),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (open) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
            Spacer(Modifier.height(8.dp))
            DetailRow("YOUR SURPLUS", t.yourSurplus)
            DetailRow("THEIR HOLE", t.theirHole)
            DetailRow("YOUR LINEUP", t.yourLineup)
            DetailRow("THEIR LINEUP", t.theirLineup)
            DetailRow("RISK", t.risk)
        }

        // No PROPOSE button yet: EspnWrite has no trade endpoint mapped, and
        // a button that silently does nothing is worse than none at all.
        Text(
            "Propose it in ESPN \u2014 the app cannot send trades yet.",
            style = inkLabel(8.5, Ink.mid),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun SideLine(playerId: Int, brief: Brief, give: Boolean) {
    val found = brief.league.teams.firstNotNullOfOrNull { t ->
        t.roster.firstOrNull { it.playerId == playerId }
    }
    val tier = found?.let {
        brief.tierOf(it.position, it.projection, it.playerId).tier
    } ?: "DEPTH"
    val dot = Color(BriefTier.hex(if (tier == "BELOW") "DEPTH" else tier))
    val label = listOfNotNull(
        found?.name,
        found?.position,
        found?.proTeamId?.let { brief.proTeams.abbrev(it) }
    ).joinToString(" ")
    val proj = found?.projection?.let {
        String.format(java.util.Locale.US, "%.1f", it)
    } ?: "\u2014"

    Row(
        Modifier.padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (give) {
            Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(2.dp))
                .background(dot))
            Spacer(Modifier.width(6.dp))
            Text("$label \u00B7 $proj", style = inkBody(11.5, Ink.paper))
        } else {
            Text("$proj \u00B7 $label", style = inkBody(11.5, Ink.paper))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(2.dp))
                .background(dot))
        }
    }
}

@Composable
private fun DetailRow(label: String, body: String?) {
    if (body.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
        Text(label, style = inkLabel(8.5, Ink.mid), modifier = Modifier.width(64.dp))
        Spacer(Modifier.width(8.dp))
        Text(body, style = inkBody(11.0, Color(0xFFC6D6E6)), modifier = Modifier.weight(1f))
    }
}

/**
 * When this brief was written, in plain sight.
 *
 * The screen has buttons on it, and a button borrows authority from the
 * reasoning beside it. If that reasoning is eighteen hours old the reader has
 * to be able to see so without going looking — so the age is stated, and it
 * changes colour as it goes off rather than sitting there in the same grey it
 * had when it was fresh.
 */
@Composable
private fun BriefHeader(daily: DailyBrief?, brief: Brief, now: Long) {
    val ts = daily?.generatedAtMillis
    val hours = ts?.let { (now - it) / 3_600_000 }
    val tone = when {
        hours == null -> Ink.mid
        hours < 6 -> Ink.positive
        hours < 18 -> Ink.accent
        else -> Ink.negative
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "WEEK ${daily?.week ?: brief.league.settings.scoringPeriodId}",
                style = inkLabel(12.0, Ink.paper)
            )
            Spacer(Modifier.width(8.dp))
            Text(brief.league.settings.name.uppercase(), style = inkLabel(10.0, Ink.mid))
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    hours == null -> "NOT PUBLISHED"
                    hours < 1 -> "JUST NOW"
                    hours == 1L -> "1 HOUR AGO"
                    hours < 24 -> "$hours HOURS AGO"
                    else -> "${hours / 24}D AGO"
                },
                style = inkLabel(9.5, tone)
            )
        }
        com.aviato.fantasybrief.data.notePostedLabel(ts)?.let {
            Text(
                "Posted $it",
                style = inkBody(11.0, Ink.mid),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
}
