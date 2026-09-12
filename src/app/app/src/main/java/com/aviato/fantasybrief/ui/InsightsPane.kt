package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
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
import com.aviato.fantasybrief.data.Brief
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
    onPlayer: (PlayerFocus) -> Unit
) {
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
            items(r.items, key = { "r-${it.kind}-${it.headline.take(24)}" }) { ins ->
                RemoteInsightCard(ins, brief, myTeamId, onPlayer)
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

@Composable
private fun RemoteInsightCard(
    ins: com.aviato.fantasybrief.data.Insight,
    brief: Brief,
    myTeamId: Int,
    onPlayer: (PlayerFocus) -> Unit
) {
    val tint = when (ins.kind.uppercase()) {
        "START", "ADD", "WAIVER" -> Ink.positive
        "SIT", "DROP", "WARNING" -> Ink.negative
        else -> Ink.accent
    }
    val player = brief.league.myTeam?.roster
        ?.firstOrNull { it.playerId == ins.playerId }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.08f))
            .then(
                if (player != null)
                    Modifier.clickable { onPlayer(player.focus(myTeamId)) }
                else Modifier
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.25f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) { Text(ins.kind.uppercase(), style = inkLabel(7.5, tint)) }
            Spacer(Modifier.width(8.dp))
            Text(
                ins.headline, style = inkBody(13.5, Ink.paper),
                modifier = Modifier.weight(1f)
            )
            ins.confidence?.let {
                Text(it.uppercase(), style = inkLabel(8.0, Ink.mid))
            }
        }
        if (ins.body.isNotBlank()) {
            Text(
                ins.body, style = inkBody(11.5, Ink.mid), lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
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
