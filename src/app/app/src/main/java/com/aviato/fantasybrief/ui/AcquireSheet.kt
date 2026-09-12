package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.RosterPlayer
import com.aviato.fantasybrief.data.WaiverClock
import com.aviato.fantasybrief.data.WirePlayer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Acquire a player, naming the drop that pays for it.
 *
 * Two things this must not let you do quietly: add without seeing what leaves,
 * and spend waiver priority without knowing whether it comes back. The second
 * is league-specific and it is the whole cost of the decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquireSheet(
    target: WirePlayer,
    brief: Brief,
    busy: Boolean,
    lastError: String?,
    onSubmit: (addId: Int, dropId: Int?, isClaim: Boolean) -> Unit,
    onSchedule: ((addId: Int, dropId: Int, firesAt: Long) -> Unit)? = null,
    clearsAt: Long? = null,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val team = brief.league.myTeam ?: return
    val settings = brief.league.settings
    val pro = brief.proTeams

    val isClaim = target.status == "WAIVERS"
    var dropId by remember { mutableStateOf<Int?>(null) }
    var confirming by remember { mutableStateOf(false) }
    // null until you choose. Nothing is preselected: spending priority and
    // waiting for the clear are genuinely different bets.
    var claimMode by remember { mutableStateOf<Boolean?>(null) }

    // A free bench slot means no drop is needed. Forcing one made every
    // add cost a player even when the roster had room.
    // The model carries starting slots but not bench capacity, so derive the
    // limit from the league: every team has the same cap, so the largest
    // roster in it IS the cap. IR players do not occupy a bench spot.
    fun active(t: com.aviato.fantasybrief.data.FantasyTeam) =
        t.roster.count { it.lineupSlotId != 21 }
    val rosterCap = brief.league.teams.maxOfOrNull { active(it) } ?: 0
    val rosterFull = active(team) >= rosterCap

    val drop = team.roster.firstOrNull { it.playerId == dropId }
    val targetProj = target.projection ?: 0.0
    val net = targetProj - (drop?.projection ?: 0.0)

    // The WHOLE roster, in roster order. The old picker showed drop candidates
    // only, which quietly hid players the app had decided you should not drop
    // — but it is your roster and the decision is yours.
    val starters = team.roster.filter { it.isStarter }
        .sortedBy { Enums.slotSortKey(it.lineupSlotId) }
    val bench = team.roster.filterNot { it.isStarter }
        .sortedByDescending { it.projection ?: 0.0 }
    val flagsFor = brief.dropCandidates.associate { it.player.playerId to it.flags }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        // Lighter than the app ground: a sheet that matches it reads as
        // part of the screen behind rather than a thing on top of it.
        containerColor = Color(0xFF16202E)
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 660.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp)
        ) {
            if (!confirming) {
            Text(
                if (isClaim) "CLAIM" else "ADD",
                style = inkLabel(10.0, Ink.accent),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Same card as the roster below, so the swap reads as like for
            // like. This one keeps the headshot — it is one player, and the
            // face is worth the space here.
            val targetTierName =
                brief.tierNameOf(target.position, target.projection, target.playerId)
            val targetTier = tierColor(
                brief.tierNameOf(target.position, target.projection, target.playerId)
            )
            Row(
                Modifier.fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (targetTierName == "LEGENDARY") Fb.TierLegendary.copy(alpha = 0.28f)
                        else targetTier.copy(alpha = 0.10f)
                    )
                    .border(1.dp, targetTier.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(targetTier))
                Box(Modifier.padding(start = 9.dp, top = 9.dp, bottom = 9.dp)) {
                    RankedHeadshot(
                        target.playerId, target.name,
                        target.ranking?.badge(), target.ranking?.delta,
                        40.dp, Ink.accent
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(
                    Modifier.weight(1f).padding(end = 9.dp, top = 9.dp, bottom = 9.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(target.name, style = inkBody(15.0, Ink.paper),
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, false))
                        Spacer(Modifier.width(6.dp))
                        Text(target.position, style = inkLabel(8.5, Ink.mid))
                        Spacer(Modifier.width(5.dp))
                        ProTeamLogo(pro.abbrev(target.proTeamId), 20.dp, Ink.accent)
                    }
                    Text(
                        listOfNotNull(
                            pro.byeWeek(target.proTeamId)?.let { "bye $it" },
                            "${fmt0(target.percentOwned)}% owned"
                        ).joinToString("  \u00B7  "),
                        style = inkBody(9.5, Ink.mid),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Column(
                    Modifier.padding(end = 12.dp, top = 9.dp, bottom = 9.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    SplitTotal(target.projection, 17.0, 11.0, Ink.paper, TextAlign.End)
                }
            }

            // The cost of a claim is league-specific. Chem resets weekly;
            // IPADE does not, and there winning drops you to last.
            if (isClaim) {
                Spacer(Modifier.height(12.dp))
                // A banner, not a bordered box — the outline read as a
                // selected option when it is only information.
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE0873A).copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ON WAIVERS", style = inkLabel(10.0, Color(0xFFE0873A)))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "your priority ${team.waiverRank} of ${settings.size}",
                        style = inkLabel(9.0, Ink.mid)
                    )
                }
                Text(
                    if (settings.waiverOrderResets)
                        "Priority resets weekly in this league, so a claim costs " +
                            "nothing beyond this week."
                    else "Priority does NOT reset in this league — winning drops " +
                        "you to last until you climb back.",
                    style = inkBody(11.0, Ink.mid), lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                if (rosterFull) "Drop to make room"
                else "Drop someone (optional \u2014 you have a free spot)",
                style = inkLabel(10.0, Ink.mid)
            )
            listOf("STARTERS" to starters, "BENCH" to bench).forEach { (label, players) ->
                if (players.isEmpty()) return@forEach
                Text(label, style = inkLabel(9.5, Ink.accent),
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                players.forEach { p ->
                    val selected = dropId == p.playerId
                    val rowTier =
                        brief.tierNameOf(p.position, p.projection, p.playerId)
                    val median = brief.replacement.elite(
                        medianPositionFor(p.lineupSlotId, p.position))
                    val delta = if (median != null && p.projection != null)
                        p.projection!! - median else null

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (p.isStarter) Enums.slot(p.lineupSlotId) else "BN",
                            style = inkLabel(9.5,
                                if (p.isStarter) Ink.accent else Ink.mid),
                            modifier = Modifier.width(30.dp),
                            textAlign = TextAlign.Center
                        )
                        Row(
                            Modifier.weight(1f)
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        selected -> Ink.accent.copy(alpha = 0.16f)
                                        p.isStarter -> Ink.tileFill
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (selected) Ink.accent else Ink.border,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { dropId = p.playerId; confirming = false }
                        ) {
                            // Tier spine, same as the Team tab.
                            Box(
                                Modifier.width(3.dp).fillMaxHeight().background(
                                    tierColor(rowTier)
                                )
                            )
                            // Team logo where the Team tab puts the headshot —
                            // this is a drop decision, not a scouting one, and
                            // twenty faces would be noise.
                            Box(Modifier.padding(start = 9.dp, top = 9.dp,
                                bottom = 9.dp)) {
                                ProTeamLogo(pro.abbrev(p.proTeamId), 30.dp, Ink.accent)
                            }
                            Spacer(Modifier.width(9.dp))

                            Column(
                                Modifier.weight(1f)
                                    .padding(end = 9.dp, top = 9.dp, bottom = 9.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(p.name, style = inkBody(13.5, Ink.paper),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, false))
                                            Spacer(Modifier.width(6.dp))
                                            Text(p.position, style = inkLabel(8.5, Ink.mid))
                                            if (!p.healthy) {
                                                Spacer(Modifier.width(5.dp))
                                                Text(p.injuryTag,
                                                    style = inkLabel(8.0, Ink.negative))
                                            }
                                        }
                                        Text(
                                            listOfNotNull(
                                                pro.byeWeek(p.proTeamId)
                                                    ?.let { "bye $it" },
                                                p.percentOwned
                                                    ?.let { "${fmt0(it)}% owned" }
                                            ).joinToString("  \u00B7  "),
                                            style = inkBody(9.5, Ink.mid),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        SplitTotal(p.projection, 17.0, 11.0,
                                            Ink.paper, TextAlign.End)
                                        Text(
                                            if (delta != null && median != null)
                                                "${signed1(delta)} vs ${fmt1(median)}"
                                            else "no median",
                                            style = inkNum(8.5, when {
                                                delta == null -> Ink.mid
                                                delta >= 0 -> Ink.positive
                                                else -> Ink.accent.copy(alpha = 0.75f)
                                            })
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            }

            if (drop != null && !confirming) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Net ${signed1(net)} projected this week",
                    style = inkNum(15.0, if (net >= 0) Ink.positive else Ink.negative)
                )
            }

            lastError?.let {
                Spacer(Modifier.height(12.dp))
                Text("ESPN said: $it", style = inkBody(12.0, Ink.negative),
                    lineHeight = 16.sp)
            }

            Spacer(Modifier.height(18.dp))
            when {
                drop == null && rosterFull ->
                    Text("Pick someone to drop.", style = inkBody(12.0, Ink.mid))
                !confirming -> {
                    SheetAction(
                        if (isClaim) "Review claim" else "Review add",
                        Ink.accent, fill = true
                    ) { confirming = true }
                }
                else -> {
                    // Only the exchange. Everything that got you here is
                    // decided; showing the picker again would just be noise
                    // between you and the button.
                    Text("ADD", style = inkLabel(9.5, Ink.positive))
                    Spacer(Modifier.height(6.dp))
                    SwapCard(
                        name = target.name,
                        position = target.position,
                        proAbbrev = pro.abbrev(target.proTeamId),
                        playerId = target.playerId,
                        projection = target.projection,
                        tier = tierColor(
                            brief.tierNameOf(
                                target.position, target.projection, target.playerId)
                        ),
                        subtitle = listOfNotNull(
                            pro.byeWeek(target.proTeamId)?.let { "bye $it" },
                            "${fmt0(target.percentOwned)}% owned"
                        ).joinToString("  \u00B7  "),
                        showFace = true,
                        rankBadge = target.ranking?.badge(),
                        rankDelta = target.ranking?.delta
                    )

                    drop?.let { d ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.width(1.dp).height(10.dp)
                                .background(Ink.border))
                            Text("\u21C5", style = inkNum(15.0, Ink.mid),
                                modifier = Modifier.padding(vertical = 2.dp))
                            Box(Modifier.width(1.dp).height(10.dp)
                                .background(Ink.border))
                        }

                        Text("DROP", style = inkLabel(9.5, Ink.negative))
                        Spacer(Modifier.height(6.dp))
                        SwapCard(
                            name = d.name,
                            position = d.position,
                            proAbbrev = pro.abbrev(d.proTeamId),
                            playerId = d.playerId,
                            projection = d.projection,
                            tier = tierColor(
                                brief.tierNameOf(d.position, d.projection, d.playerId)
                            ),
                            subtitle = listOfNotNull(
                                if (d.isStarter) Enums.slot(d.lineupSlotId) else "bench",
                                pro.byeWeek(d.proTeamId)?.let { "bye $it" }
                            ).joinToString("  \u00B7  "),
                            showFace = d.positionId != 16,
                            rankBadge = brief.rankings[d.playerId]?.badge(),
                            rankDelta = brief.rankings[d.playerId]?.delta
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Net ${signed1(net)} projected this week",
                        style = inkNum(14.0, if (net >= 0) Ink.positive else Ink.negative)
                    )

                    if (isClaim && clearsAt != null && onSchedule != null) {
                        Spacer(Modifier.height(16.dp))
                        Text("How do you want him?", style = inkLabel(9.5, Ink.mid))
                        Spacer(Modifier.height(8.dp))
                        ChoiceRow(
                            selected = claimMode == true,
                            title = "Claim on waivers",
                            body = "Beats anyone who does not claim. Spends your " +
                                "priority.",
                            tint = Color(0xFFE0873A)
                        ) { claimMode = true }
                        Spacer(Modifier.height(8.dp))
                        ChoiceRow(
                            selected = claimMode == false,
                            title = "Schedule for when he clears",
                            body = "Around ${WaiverClock.describe(clearsAt)}. Costs no " +
                                "priority, but loses to anyone who claims.",
                            tint = Ink.positive
                        ) { claimMode = false }
                    }

                    Spacer(Modifier.height(14.dp))
                    val needsChoice = isClaim && clearsAt != null &&
                        onSchedule != null && claimMode == null
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(2f)) {
                            SheetAction(
                                when {
                                    busy -> "Sending..."
                                    needsChoice -> "Pick one above"
                                    claimMode == false -> "Schedule it"
                                    else -> "Confirm on ESPN"
                                },
                                if (needsChoice) Ink.mid else Ink.positive,
                                fill = !needsChoice
                            ) {
                                if (busy || needsChoice) return@SheetAction
                                if (claimMode == false && clearsAt != null &&
                                    drop != null && onSchedule != null
                                ) {
                                    onSchedule(target.playerId, drop.playerId, clearsAt)
                                } else {
                                    onSubmit(target.playerId, drop?.playerId, isClaim)
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            SheetAction("Back", Ink.mid) { confirming = false }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card used for both the incoming player and the outgoing one.
 *
 * Same shape in the picker and the review, so the swap reads as like for
 * like — two players of stated tiers, not a form and then a summary.
 */
@Composable
private fun SwapCard(
    name: String,
    position: String,
    proAbbrev: String,
    playerId: Int,
    projection: Double?,
    tier: Color,
    subtitle: String,
    showFace: Boolean,
    rankBadge: String? = null,
    rankDelta: Int? = null
) {
    Row(
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(9.dp))
            .background(tier.copy(alpha = 0.10f))
            .border(1.dp, tier.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tier))
        Box(Modifier.padding(start = 9.dp, top = 9.dp, bottom = 9.dp)) {
            RankedHeadshot(
                playerId, name, rankBadge, rankDelta, 40.dp, Ink.accent,
                isDst = !showFace, proAbbrev = proAbbrev
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(
            Modifier.weight(1f).padding(end = 9.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = inkBody(15.0, Ink.paper),
                    fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(6.dp))
                Text(position, style = inkLabel(8.5, Ink.mid))
                Spacer(Modifier.width(5.dp))
                ProTeamLogo(proAbbrev, 20.dp, Ink.accent)
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = inkBody(9.5, Ink.mid),
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Column(
            Modifier.padding(end = 12.dp, top = 9.dp, bottom = 9.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            SplitTotal(projection, 17.0, 11.0, Ink.paper, TextAlign.End)
        }
    }
}

/** An explicit pick. Neither option is a default — they are different bets. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    body: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) tint.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) tint else Ink.border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(16.dp).height(16.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, if (selected) tint else Ink.mid,
                    androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier.width(8.dp).height(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(tint)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = inkBody(13.0, Ink.paper))
            Text(body, style = inkBody(10.5, Ink.mid), lineHeight = 14.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SheetAction(
    label: String, color: Color, fill: Boolean = false, onClick: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (fill) color else Color.Transparent)
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = inkLabel(11.0, if (fill) Ink.ground else color),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
