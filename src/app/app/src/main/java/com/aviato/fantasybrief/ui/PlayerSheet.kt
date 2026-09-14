package com.aviato.fantasybrief.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.ActivityLog
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.RosterPlayer
import com.aviato.fantasybrief.data.TxKind
import com.aviato.fantasybrief.data.WirePlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box

/** One shape for any player, wherever they were tapped. */
data class PlayerFocus(
    val playerId: Int,
    val name: String,
    val position: String,
    val proTeamId: Int,
    val projection: Double?,
    val percentOwned: Double?,
    val percentChange: Double?,
    val injuryStatus: String?,
    val healthy: Boolean,
    val pointsPerGame: Double? = null,
    val gamesPlayed: Int = 0,
    val percentStarted: Double? = null,
    /** week -> actual points. Already in memory; no extra request. */
    val weeklyActuals: Map<Int, Double> = emptyMap(),
    val ownerTeamId: Int?,
    val availability: String?
)

fun RosterPlayer.focus(ownerTeamId: Int) = PlayerFocus(
    playerId, name, position, proTeamId, projection, percentOwned, percentChange,
    injuryStatus, healthy, pointsPerGame, gamesPlayed, null, weeklyActuals,
    ownerTeamId, null
)

fun WirePlayer.focus() = PlayerFocus(
    playerId, name, position, proTeamId, projection, percentOwned, percentChange,
    injuryStatus, healthy, null, 0, percentStarted, weeklyActuals, null,
    if (status == "WAIVERS") "WAIVERS" else "FREE AGENT"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    focus: PlayerFocus,
    brief: Brief,
    onAcquire: (() -> Unit)? = null,
    /**
     * Everything written about this player, including bench and other teams'.
     *
     * A list is the wrong home for a note about one player: you read it once,
     * then it scrolls away and the player it describes lives somewhere else.
     * Attached to the player, it is there whenever you look him up.
     */
    notes: List<com.aviato.fantasybrief.data.Insight> = emptyList(),
    /** When the payload these came from was written. */
    notesAt: Long? = null,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pro = brief.proTeams
    val week = brief.league.settings.scoringPeriodId
    val level = brief.replacement.level(focus.position)
    val rank = brief.tierNameOf(focus.position, focus.projection, focus.playerId)
    val owner = brief.league.teams.firstOrNull { it.id == focus.ownerTeamId }
    val isMine = focus.ownerTeamId != null && focus.ownerTeamId == brief.league.myTeamId

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Fb.Raised
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp)
        ) {
            // Headshot beside the whole title block, not just the name —
            // 258 KB each, so this belongs on a detail screen and nowhere
            // that renders twelve rows.
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankedHeadshot(
                    focus.playerId, focus.name,
                    brief.rankings[focus.playerId]?.badge(),
                    brief.rankings[focus.playerId]?.delta, 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(focus.name, color = Fb.Ink, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold)
                    MetaRow(
                        focus.position to Fb.Muted,
                        pro.abbrev(focus.proTeamId) to Fb.Muted,
                        (pro.opponent(focus.proTeamId, week) ?: "") to Fb.Faint,
                        (pro.byeWeek(focus.proTeamId)?.let { "bye $it" } ?: "")
                            to Fb.Faint
                    )
                }
            }

            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                notes.forEach { n ->
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Fb.Teal.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            n.verdict?.let {
                                Text(it.uppercase(), color = Fb.Teal, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(n.section.replace('_', ' '), color = Fb.Faint,
                                fontSize = 10.sp)
                        }
                        Text(n.headline, color = Fb.Ink, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        n.evidence?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Fb.Muted, fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        if (n.body.isNotBlank()) {
                            Text(n.body, color = Fb.Muted, fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        // Two different clocks, and confusing them is the whole
                        // risk: sourceAt is when the reporting was published,
                        // notesAt is when this was written off the back of it.
                        val posted = com.aviato.fantasybrief.data
                            .notePostedLabel(notesAt)
                        val sourced = com.aviato.fantasybrief.data
                            .notePostedLabel(n.sourceAtMillis)
                        if (posted != null || sourced != null) {
                            Text(
                                listOfNotNull(
                                    posted?.let { "posted $it" },
                                    sourced?.let { "source $it" }
                                ).joinToString("  \u00B7  "),
                                color = Fb.Faint, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Stat(focus.projection?.let { fmt1(it) } ?: "—", size = 34)
                    Text("projected", color = Fb.Faint, fontSize = 10.sp)
                }
                focus.pointsPerGame?.let { ppg ->
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Stat(fmt1(ppg), size = 34, color = Fb.Green)
                        Text("actual per game (${focus.gamesPlayed}g)",
                            color = Fb.Faint, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    ProjectionScale(focus.projection, level, barWidth = 120.dp)
                    level?.let {
                        Text(
                            "solid ${fmt1(it.solidLine)}   ·   elite ${fmt1(it.eliteLine)}",
                            color = Fb.Faint, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rank?.let { TagPill(it, tierColor(it)) }
                if (!focus.healthy) TagPill(Enums.injuryShort(focus.injuryStatus), Fb.Red)
                focus.availability?.let { av ->
                    val onWaivers = av == "WAIVERS"
                    TagPill(
                        if (onWaivers) "WAIVERS" else "FREE AGENT",
                        if (onWaivers) Color(0xFFE0873A) else Fb.Green
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Fb.Rule)
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Column {
                    Text("Rostered by", color = Fb.Faint, fontSize = 10.sp)
                    Text(
                        when {
                            isMine -> "You"
                            owner != null -> owner.name
                            else -> "Nobody"
                        },
                        color = if (isMine) Fb.Teal else Fb.Ink, fontSize = 14.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        focus.percentStarted?.let { "Owned / started" }
                            ?: "Owned across ESPN",
                        color = Fb.Faint, fontSize = 10.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stat(focus.percentOwned?.let { fmtPct(it) } ?: "—", size = 14)
                        focus.percentStarted?.let {
                            Text(" / ", color = Fb.Faint, fontSize = 12.sp)
                            Stat(fmtPct(it), size = 14, color = Fb.Muted)
                        }
                        focus.percentChange?.let {
                            if (kotlin.math.abs(it) >= 0.1) {
                                Spacer(Modifier.width(6.dp))
                                Stat(
                                    fmtDelta(it), size = 12,
                                    color = if (it > 0) Fb.Teal else Fb.Faint
                                )
                            }
                        }
                    }
                }
            }

            // Week by week, every week of the season.
            //
            // An empty section teaches nothing; a full list with blanks shows
            // the shape about to be filled in, and marks the bye so a zero is
            // never mistaken for a bad game.
            //
            // Past weeks are FREE — weeklyActuals is already parsed from the
            // response that built this screen. Future weeks are not: each one
            // costs a ~1.2MB request, so fifteen of them would be 18MB per tap.
            run {
                Spacer(Modifier.height(20.dp))
                Text("Week by week", color = Fb.Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                val currentWeek = brief.league.settings.scoringPeriodId
                val bye = brief.proTeams.byeWeek(focus.proTeamId)
                val best = (focus.weeklyActuals.values.maxOrNull()
                    ?: focus.projection ?: 1.0).coerceAtLeast(1.0)

                (1..17).forEach { w ->
                    val pts = focus.weeklyActuals[w]
                    val isBye = bye == w
                    val isNow = w == currentWeek
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "W$w",
                            color = if (isNow) Fb.Teal else Fb.Faint,
                            fontSize = 11.sp,
                            modifier = Modifier.width(34.dp)
                        )
                        Box(
                            Modifier.weight(1f).height(13.dp)
                                .background(Fb.Rule.copy(alpha = 0.5f),
                                    RoundedCornerShape(3.dp))
                        ) {
                            if (pts != null) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(
                                            (pts / best).toFloat().coerceIn(0.02f, 1f)
                                        )
                                        .height(13.dp)
                                        .background(
                                            if (pts >= (focus.projection ?: 0.0))
                                                Fb.Green else Fb.TierSolid,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.width(46.dp)) {
                            when {
                                pts != null -> Stat(fmt1(pts), size = 13)
                                isBye -> Text("BYE", color = Fb.Red, fontSize = 10.sp)
                                w < currentWeek ->
                                    Text("\u2014", color = Fb.Faint, fontSize = 12.sp)
                                isNow -> Text(
                                    focus.projection?.let { "${fmt1(it)} proj" } ?: "\u2014",
                                    color = Fb.Muted, fontSize = 10.sp
                                )
                                else -> Text("", fontSize = 10.sp)
                            }
                        }
                    }
                }

                Text(
                    focus.pointsPerGame?.let {
                        "${fmt1(it)} per game over ${focus.gamesPlayed} " +
                            "game${if (focus.gamesPlayed == 1) "" else "s"}. " +
                            "Green beats this week's projection."
                    } ?: "No games played yet. Only the current week is projected — " +
                        "forward weeks cost a request each and are not fetched here.",
                    color = Fb.Faint, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            // Everything ESPN has logged            // Everything ESPN has logged            // Everything ESPN has logged about this player in this league.
            val history = brief.transactions
                .filter { it.playerId == focus.playerId && it.kind != TxKind.LINEUP }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("In this league", color = Fb.Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                history.take(6).forEach { tx ->
                    val teamName = brief.league.teams
                        .firstOrNull { it.id == tx.teamId }?.name ?: "team ${tx.teamId}"
                    val verb = when (tx.kind) {
                        TxKind.DROP -> "dropped by"
                        TxKind.TRADE -> "trade offer involving"
                        TxKind.WAIVER_ADD -> "claimed by"
                        else -> "added by"
                    }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            SimpleDateFormat("MMM d", Locale.US).format(Date(tx.whenMillis)),
                            color = Fb.Faint, fontSize = 11.sp,
                            modifier = Modifier.width(50.dp)
                        )
                        Text("$verb $teamName", color = Fb.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
