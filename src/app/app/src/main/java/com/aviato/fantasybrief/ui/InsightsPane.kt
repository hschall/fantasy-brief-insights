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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.Impact
import com.aviato.fantasybrief.data.Insight
import com.aviato.fantasybrief.data.WirePlayer
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.PlayerRanking
import com.aviato.fantasybrief.data.RosterPlayer

/**
 * What the analysts think, against what ESPN projects.
 *
 * The app's own numbers are projections. This is the OUTSIDE view — eight
 * sources ranking every player independently — and the useful moments are
 * where it disagrees with the lineup you already have. A starter the
 * consensus ranks below two of your bench players is a decision the
 * projection alone will never surface.
 *
 * Sources are anonymous: ESPN exposes numeric ids and no name lookup, so this
 * reports consensus and spread rather than attributing opinions.
 */
@Composable
fun InsightsPane(
    brief: Brief,
    /** Written against this league's uploaded dump. Null until fetched. */
    remote: com.aviato.fantasybrief.data.InsightPayload? = null,
    bottomInset: Dp,
    onPlayer: (PlayerFocus) -> Unit,
    /** Same callback AtRiskCard uses — opens the existing AcquireSheet. */
    onAcquire: ((WirePlayer) -> Unit)? = null,
    /** Opens LineupSheet with the pairing already chosen. */
    onSwapTo: ((RosterPlayer, RosterPlayer) -> Unit)? = null
) {
    // One clock read for the pane. Two reads a moment apart can disagree about
    // whether a deadline has passed, and a card that sorts as live while its
    // button refuses is worse than either answer on its own.
    val now = remember(remote) { System.currentTimeMillis() }
    val team = brief.league.myTeam ?: return
    val myTeamId = team.id
    val ranks = brief.rankings

    data class Row2(val p: RosterPlayer, val r: PlayerRanking?)

    val starters = remember(brief) {
        team.roster.filter { it.isStarter }
            .sortedBy { Enums.slotSortKey(it.lineupSlotId) }
            .map { Row2(it, ranks[it.playerId]) }
    }
    val bench = remember(brief) {
        team.roster.filterNot { it.isStarter }
            .map { Row2(it, ranks[it.playerId]) }
    }


    val upgrades = remember(brief) {
        starters.mapNotNull { s ->
            val sr = s.r ?: return@mapNotNull null
            val better = bench.filter { b ->
                b.p.position == s.p.position && b.r != null &&
                    b.r.consensus < sr.consensus - 2 && b.p.healthy
            }.sortedBy { it.r!!.consensus }
            if (better.isEmpty()) null else s to better
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Text(
                    ranks.values.firstOrNull()?.let { r ->
                        if (r.preseason)
                            "${ranks.size} players \u00B7 PRESEASON ranks \u2014 " +
                                "this week has not been ranked yet"
                        else "${ranks.size} players ranked by ${r.sourceCount} " +
                            "sources \u00B7 week ${r.period}"
                    } ?: "No rankings available",
                    style = inkBody(11.0, Ink.mid)
                )
                Text(
                    "ESPN projects. These are other people's opinions, and the " +
                        "disagreements are the point.",
                    style = inkBody(10.5, Ink.mid.copy(alpha = 0.75f)),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        remote?.takeIf { it.items.isNotEmpty() }?.let { r ->
            item {
                InkSectionRow(
                    "ANALYSIS",
                    r.hoursOld?.let { h -> if (h < 1) "JUST IN" else "${h}H AGO" }
                )
            }
            if (r.summary.isNotBlank()) {
                item {
                    Text(
                        r.summary,
                        style = inkBody(12.5, Ink.paper), lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
            items(
                r.ordered(now),
                key = { "r-${it.kind}-${it.headline.take(24)}" }
            ) { ins ->
                RemoteInsightCard(
                    ins, brief, myTeamId, now, onPlayer, onAcquire, onSwapTo
                )
            }
            val orphaned = r.items.count { ins ->
                ins.playerId != null &&
                    brief.league.teams.none { t ->
                        t.roster.any { it.playerId == ins.playerId }
                    } && brief.pool.none { it.playerId == ins.playerId }
            }
            if (orphaned > 0) {
                item {
                    Text(
                        "$orphaned item(s) name a player id nobody in this " +
                            "league has — those will not attach to a card.",
                        style = inkBody(10.5, Ink.negative),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (upgrades.isNotEmpty()) {
            item { InkSectionRow("WORTH A LOOK", "${upgrades.size}") }
            items(upgrades, key = { "u-${it.first.p.playerId}" }) { (starter, better) ->
                UpgradeCard(
                    myTeamId, starter.p, starter.r!!,
                    better.map { it.p to it.r!! }, onPlayer
                )
            }
        }


        item { InkSectionRow("YOUR STARTERS", null) }
        items(starters, key = { "s-${it.p.playerId}" }) { row ->
            RankRow(myTeamId, row.p, row.r, brief, true, onPlayer)
        }

        item { InkSectionRow("BENCH", null) }
        items(bench, key = { "b-${it.p.playerId}" }) { row ->
            RankRow(myTeamId, row.p, row.r, brief, false, onPlayer)
        }
    }
}

/**
 * Fanta orange, outline only.
 *
 * The same treatment the legendary player tiles use, and for the same reason:
 * an orange tint over this ground reads brown. It is a border or it is
 * nothing.
 */
private val Legendary = Color(0xFFFF7900)

/** How long is left, without raising a timezone question. */
private fun timeLeft(expiresAt: Long?, now: Long): String? {
    if (expiresAt == null) return null
    val ms = expiresAt - now
    if (ms <= 0) return "EXPIRED"
    val h = ms / 3_600_000
    return when {
        h >= 24 -> "${h / 24}D LEFT"
        h >= 1 -> "${h}H LEFT"
        else -> "${(ms % 3_600_000) / 60_000}M LEFT"
    }
}

@Composable
private fun RemoteInsightCard(
    ins: Insight,
    brief: Brief,
    myTeamId: Int,
    now: Long,
    onPlayer: (PlayerFocus) -> Unit,
    onAcquire: ((WirePlayer) -> Unit)?,
    onSwapTo: ((RosterPlayer, RosterPlayer) -> Unit)?
) {
    // Eight cards of full prose is a wall nobody reads. The evidence is why
    // the verdict is true and stays visible; the body is what to do about it
    // and waits for a tap. Collapsed by default, because the common case is
    // scanning the list, not studying one card.
    val open = remember(ins.headline) { androidx.compose.runtime.mutableStateOf(false) }
    val expired = ins.isExpired(now)
    val quiet = expired || ins.impact == Impact.WATCH

    val edge = when {
        expired -> Ink.border
        ins.impact == Impact.LEGENDARY -> Legendary
        ins.impact == Impact.ELITE -> Ink.accent
        else -> Ink.border
    }
    val ink = if (quiet) Ink.mid else Ink.paper

    // The subject may sit on any roster or on the wire — an at-risk card is
    // usually about somebody else's starter.
    val player = brief.league.teams.firstNotNullOfOrNull { t ->
        t.roster.firstOrNull { it.playerId == ins.playerId }?.let { t.id to it }
    }
    val rank = ins.playerId?.let { brief.rankings[it] }

    // A swap moves two players I already own; everything else reaches the
    // wire. Different destinations, so they resolve separately.
    val isSwap = ins.action?.type.equals("SWAP", true)
    val mine = brief.league.myTeam?.roster.orEmpty()
    val swapSubject = ins.action?.playerId?.let { id ->
        mine.firstOrNull { it.playerId == id }
    }
    val swapTarget = ins.action?.dropPlayerId?.let { id ->
        mine.firstOrNull { it.playerId == id }
    }
    val target = ins.action?.playerId?.takeIf { !isSwap }?.let { id ->
        brief.pool.firstOrNull { it.playerId == id }
    }
    val canAct = ins.isActionable(now) && when {
        isSwap -> swapSubject != null && swapTarget != null && onSwapTo != null
        else -> target != null && onAcquire != null
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (quiet) Color.Transparent else Ink.accent.copy(alpha = 0.06f))
            .border(if (ins.impact == Impact.LEGENDARY && !expired) 1.dp else 0.5.dp,
                edge, RoundedCornerShape(10.dp))
            .clickable { open.value = !open.value }
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (ins.playerId != null) {
              Box(
                Modifier.then(
                    if (player != null)
                        Modifier.clickable {
                            onPlayer(player.second.focus(player.first))
                        }
                    else Modifier
                )
              ) {
                RankedHeadshot(
                    ins.playerId, player?.second?.name ?: "",
                    rank?.badge(), rank?.delta, 38.dp,
                    if (expired) Ink.mid else edge,
                    // D/ST ids are negative by design and have no headshot —
                    // the team logo is the only picture there is.
                    isDst = ins.playerId < 0,
                    proAbbrev = player?.second?.proTeamId
                        ?.let { brief.proTeams.abbrev(it) } ?: ""
                )
              }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        ins.headline, style = inkBody(13.5, ink),
                        modifier = Modifier.weight(1f)
                    )
                    if (ins.body.isNotBlank()) {
                        Text(
                            if (open.value) "\u25B4" else "\u25BE",
                            style = inkLabel(10.0, Ink.mid),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ins.verdict?.let {
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp))
                                .background(edge.copy(alpha = 0.18f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) { Text(it.uppercase(), style = inkLabel(8.0, if (quiet) Ink.mid else edge)) }
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(ins.section.replace('_', ' '), style = inkLabel(8.0, Ink.mid))
                    ins.impactPoints?.let {
                        Spacer(Modifier.width(7.dp))
                        Text("+${fmt1(it)} PTS", style = inkLabel(8.0, Ink.mid))
                    }
                }
            }
        }
        ins.evidence?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = inkBody(11.0, Ink.mid), lineHeight = 15.sp,
                maxLines = if (open.value) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (ins.body.isNotBlank() && open.value) {
            Text(
                ins.body, style = inkBody(11.5, if (quiet) Ink.mid else Ink.paper),
                lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }

        val left = timeLeft(ins.expiresAtMillis, now)
        if (left != null || ins.action != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    left ?: "NO DEADLINE",
                    style = inkLabel(8.5, if (expired) Ink.negative else Ink.mid)
                )
                // Past its window the button is gone, not greyed. A disabled
                // control invites a tap; an absent one cannot mislead.
                if (canAct) {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .border(0.5.dp, Ink.accent, RoundedCornerShape(20.dp))
                            .clickable {
                                if (isSwap) onSwapTo?.invoke(swapSubject!!, swapTarget!!)
                                else onAcquire?.invoke(target!!)
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text(ins.action!!.label.uppercase(), style = inkLabel(9.0, Ink.accent)) }
                } else if (ins.action != null) {
                    Text(
                        if (expired) "WINDOW CLOSED" else "NOT AVAILABLE",
                        style = inkLabel(8.5, Ink.mid)
                    )
                }
            }
        }
    }
}


@Composable
private fun UpgradeCard(
    myTeamId: Int,
    starter: RosterPlayer,
    starterRank: PlayerRanking,
    better: List<Pair<RosterPlayer, PlayerRanking>>,
    onPlayer: (PlayerFocus) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.accent.copy(alpha = 0.10f))
            .padding(13.dp)
    ) {
        Text(
            "You are starting ${starter.name} at ${starter.position}",
            style = inkBody(13.5, Ink.paper)
        )
        Text(
            "Consensus has him ${starterRank.label()}. On your bench:",
            style = inkBody(11.0, Ink.mid),
            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)
        )
        better.take(3).forEach { (p, r) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPlayer(p.focus(myTeamId)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.name, style = inkBody(13.0, Ink.paper),
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(r.label(), style = inkNum(14.0, Ink.positive))
            }
        }
    }
}

@Composable
private fun RankRow(
    myTeamId: Int,
    p: RosterPlayer,
    r: PlayerRanking?,
    brief: Brief,
    isStarter: Boolean,
    onPlayer: (PlayerFocus) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onPlayer(p.focus(myTeamId)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isStarter) Enums.slot(p.lineupSlotId) else "BN",
            style = inkLabel(9.0, if (isStarter) Ink.accent else Ink.mid),
            modifier = Modifier.width(34.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = inkBody(13.5, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(6.dp))
                Text(brief.proTeams.abbrev(p.proTeamId), style = inkLabel(8.5, Ink.mid))
            }
            Text(
                when {
                    r == null -> "not ranked"
                    // A wide spread means the sources genuinely disagree, and
                    // that is worth knowing before trusting the median.
                    r.contested -> "${r.spread()} \u00B7 sources disagree"
                    else -> r.spread()
                },
                style = inkBody(10.0, if (r?.contested == true) Ink.accent else Ink.mid),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                r?.label() ?: "\u2014",
                style = inkNum(15.0, if (r == null) Ink.mid else Ink.paper)
            )
            Text(
                p.projection?.let { "${fmt1(it)} proj" } ?: "",
                style = inkNum(9.5, Ink.mid)
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
}
