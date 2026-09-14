package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.AtRiskPair
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.WirePlayer

/**
 * The starter and the man behind him, side by side.
 *
 * A single line saying "X is out and Y is free" makes you hold two players in
 * your head. Showing the substitution is the thing being evaluated, so the
 * card shows it: who is at risk on the left, who inherits on the right, with
 * the same fields on both so they compare directly.
 */
@Composable
fun AtRiskCard(
    pair: AtRiskPair,
    brief: Brief,
    /**
     * Researched verdict on this starter, when there is one.
     *
     * certaintyLabel is derived from the designation string alone — every
     * QUESTIONABLE in the league reads "Genuinely uncertain", which is true
     * and useless. A designation is a label, not a probability, and only
     * practice reports settle it. When a verdict exists it replaces that line
     * and brings its evidence with it.
     */
    insight: com.aviato.fantasybrief.data.Insight? = null,
    onPlayer: (PlayerFocus) -> Unit,
    onAcquire: ((WirePlayer) -> Unit)?
) {
    val pro = brief.proTeams
    val week = brief.league.settings.scoringPeriodId
    // Red when it costs me points, green when it is someone else's problem
    // and therefore my opportunity.

    // TIER is the loud channel — it is what you scan for. Ownership moves
    // to a rail on the left plus a label, so both facts survive without the
    // border trying to carry two meanings at once.
    val tier = brief.tierNameOf(
        pair.starter.position, pair.starter.projection, pair.starter.playerId)
    val tint = tierColor(tier)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
tint.copy(alpha = 0.08f)
            )
            .border(1.dp, tint.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
    ) {
    Column(Modifier.weight(1f)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ownership reads as a filled pill against an outlined one —
            // a shape difference, so it never competes with the tier hue.
            if (pair.isMine) {
                Box(
                    Modifier.clip(RoundedCornerShape(3.dp))
                        .background(tint)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("YOURS", style = inkLabel(8.5, Ink.ground)) }
            } else {
                Box(
                    Modifier.clip(RoundedCornerShape(3.dp))
                        .border(1.dp, Ink.mid, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(pair.ownerName.uppercase(), style = inkLabel(8.5, Ink.mid),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            tier?.let { Text(it, style = inkLabel(9.0, tint)) }
            Spacer(Modifier.weight(1f))
            if (pair.backupTrending) {
                Box(
                    Modifier.clip(RoundedCornerShape(3.dp))
                        .background(Ink.positive)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        "\u25B2 ${fmt1(pair.backup.percentChange)}% TODAY",
                        style = inkLabel(8.0, Ink.ground)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (pair.onWaivers) "ON WAIVERS" else "FREE AGENT",
                style = inkLabel(9.0, if (pair.onWaivers) Ink.accent else Ink.positive)
            )
        }

        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // At risk
            Row(
                Modifier.weight(1f).clickable {
                    onPlayer(pair.starter.focus(pair.ownerTeamId))
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
            RankedHeadshot(
                pair.starter.playerId, pair.starter.name,
                brief.rankings[pair.starter.playerId]?.badge(),
                brief.rankings[pair.starter.playerId]?.delta,
                36.dp, Ink.negative
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(pair.starter.name, style = inkBody(14.0, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${pair.starter.position}1", style = inkLabel(8.5, Ink.mid))
                    Text(pro.abbrev(pair.proTeamId), style = inkLabel(8.5, Ink.mid))
                    Box(
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .border(
                                1.dp,
                                if (pair.certainty >= 70) Ink.negative else Ink.accent,
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(pair.starter.injuryTag.ifBlank { "OUT" },
                            style = inkLabel(8.0,
                                if (pair.certainty >= 70) Ink.negative else Ink.accent))
                    }
                }
                Text(
                    "${fmt1(pair.vacating)} projected \u00B7 vacating",
                    style = inkNum(9.5, Ink.mid),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    insight?.verdict ?: pair.certaintyLabel,
                    style = inkBody(10.0, when {
                        insight?.verdict != null -> Ink.paper
                        pair.certainty >= 70 -> Ink.negative
                        else -> Ink.mid
                    }),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            }

            Column(
                Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("\u2192", style = inkNum(15.0, tint))
            }

            // Inherits
            Row(
                Modifier.weight(1f).clickable { onPlayer(pair.backup.focus()) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End) {
                Text(pair.backup.name, style = inkBody(14.0, Ink.paper),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End)
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pair.backupDepthRank?.let {
                        Text("${pair.backup.position}$it", style = inkLabel(8.5, Ink.mid))
                    }
                    Text("${fmt0(pair.backup.percentOwned)}% owned",
                        style = inkLabel(8.5, Ink.mid))
                    if (pair.backup.percentChange >= 1.0) {
                        Text("\u25B2${fmt1(pair.backup.percentChange)}",
                            style = inkNum(8.5, Ink.positive))
                    }
                }
                if (pair.backupTrending) {
                    Text(
                        "others are already claiming him",
                        style = inkBody(10.0, Ink.positive),
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Text(
                    // The projection still prices him as a backup, which is
                    // the whole reason he is available.
                    "${fmt1(pair.backup.projection ?: 0.0)} projected \u00B7 as a backup",
                    style = inkNum(9.5, Ink.mid),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            RankedHeadshot(
                pair.backup.playerId, pair.backup.name,
                pair.backup.ranking?.badge(), pair.backup.ranking?.delta,
                36.dp, Ink.positive
            )
            }
        }

        onAcquire?.let { f ->
            Box(
                Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = 0.20f))
                    .border(1.dp, tint, RoundedCornerShape(6.dp))
                    .clickable { f(pair.backup) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (pair.onWaivers) "CLAIM OR SCHEDULE ${pair.backup.name.uppercase()}"
                    else "ADD ${pair.backup.name.uppercase()} NOW",
                    style = inkLabel(10.5, Ink.paper), maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    }
}
