package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.LineupValidator
import com.aviato.fantasybrief.data.RosterPlayer

/**
 * A candidate swap: who moves, and where each ends up.
 */
private data class SwapOption(
    val other: RosterPlayer?,     // null for an empty slot
    val targetSlot: Int,
    val locked: Boolean,
    val note: String?
)

/**
 * Swap a player into another slot, listing PLAYERS rather than slots.
 *
 * Listing slots only worked from the bench: from the QB slot the only other
 * eligible slot is the bench, so the sheet offered one option. What's wanted
 * is "who can I put here instead", which is a list of people.
 *
 * MUTUAL ELIGIBILITY is the rule that makes flex correct. For A to take B's
 * slot, A must be eligible for B's slot AND B must be eligible for A's — the
 * transaction moves both. In Chem the FLEX is slot 3 (RB/WR only), so a tight
 * end cannot enter it even though he is a legal starter at TE. Both checks
 * read eligibleSlots from ESPN rather than assuming anything about the league.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineupSheet(
    player: RosterPlayer,
    brief: Brief,
    busy: Boolean,
    lastError: String?,
    onApply: (List<Triple<Int, Int, Int>>) -> Unit,
    ink: Boolean = false,
    /** Preselect this swap and go straight to review. The caller already
     *  named the pairing, so making you pick it again is busywork. */
    preselect: RosterPlayer? = null,
    onDismiss: () -> Unit
) {
    // Opened from Matchup it wears the Ink palette; from Team, the app's.
    val cBg = if (ink) Ink.ground else Fb.Raised
    val cText = if (ink) Ink.paper else Fb.Ink
    val cDim = if (ink) Ink.mid else Fb.Muted
    val cFaint = if (ink) Ink.mid else Fb.Faint
    val cAccent = if (ink) Ink.accent else Fb.Teal
    val cBad = if (ink) Ink.negative else Fb.Red
    val cWarn = if (ink) Ink.accent else Fb.Amber
    val cRule = if (ink) Ink.border else Fb.Rule
    val cGo = if (ink) Ink.positive else Fb.Green
    val cOn = if (ink) Ink.ground else Fb.Navy

    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val team = brief.league.myTeam ?: return
    val settings = brief.league.settings
    val pro = brief.proTeams
    val week = settings.scoringPeriodId

    val preselected = preselect != null
    var chosen by remember { mutableStateOf<SwapOption?>(null) }
    var confirming by remember { mutableStateOf(false) }

    val options = remember(player.playerId, team) {
        buildSwapOptions(player, team, brief)
    }

    // Land on the review step when the caller already knows the pairing.
    androidx.compose.runtime.LaunchedEffect(preselect?.playerId) {
        val target = preselect ?: return@LaunchedEffect
        options.firstOrNull { it.other?.playerId == target.playerId }?.let {
            chosen = it
            confirming = true
        }
    }

    val moves: List<Triple<Int, Int, Int>> = chosen?.let { option ->
        buildList {
            add(Triple(player.playerId, player.lineupSlotId, option.targetSlot))
            option.other?.let {
                add(Triple(it.playerId, option.targetSlot, player.lineupSlotId))
            }
        }
    } ?: emptyList()

    val problems = chosen?.let { option ->
        val proposed = buildMap {
            put(player.playerId, option.targetSlot)
            option.other?.let { put(it.playerId, player.lineupSlotId) }
        }
        LineupValidator.check(team, settings, pro, week, proposed)
    } ?: emptyList()

    val blocked = problems.any { it.severity == "BLOCK" }
    val playerLocked = pro.hasKickedOff(player.proTeamId, week)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = cBg
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp)
        ) {
            Text("Swap ${player.name}", color = cText, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            MetaRow(
                "currently ${Enums.slot(player.lineupSlotId)}" to cAccent,
                player.position to cDim,
                pro.abbrev(player.proTeamId) to cFaint,
                (pro.kickoffLabel(player.proTeamId, week) ?: "") to cAccent,
                (player.projection?.let { "proj ${fmt1(it)}" } ?: "") to cFaint
            )

            if (playerLocked) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "His game has already started — ESPN will refuse any move.",
                    color = cBad, fontSize = 12.sp, lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("Swap with", color = cFaint, fontSize = 10.sp)

            if (options.isEmpty() && !preselected) {
                Text(
                    "Nobody on this roster can legally take " +
                        "${Enums.slot(player.lineupSlotId)} and hold a slot " +
                        "${player.name} is eligible for.",
                    color = cDim, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (!preselected) {
            options.forEach { option ->
                val selected = chosen?.targetSlot == option.targetSlot &&
                    chosen?.other?.playerId == option.other?.playerId
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (selected) cAccent.copy(alpha = 0.12f) else Color.Transparent
                        )
                        .clickable(enabled = !option.locked) {
                            chosen = option; confirming = false
                        }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Enums.slot(option.targetSlot),
                        color = if (selected) cAccent else cFaint,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(46.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.other?.name ?: "(empty slot)",
                            color = if (option.locked) cFaint
                                    else if (ink) cText else tierTextColor(
                                        option.other?.let {
                                            brief.tierNameOf(
                                                it.position, it.projection, it.playerId)
                                        }
                                    ),
                            fontSize = 14.sp
                        )
                        option.note?.let {
                            Text(it, color = if (option.locked) cFaint else cWarn,
                                fontSize = 10.sp)
                        }
                    }
                    option.other?.projection?.let {
                        val diff = it - (player.projection ?: 0.0)
                        Column(horizontalAlignment = Alignment.End) {
                            StatCell(fmt1(it), size = 13)
                            Text(
                                if (diff >= 0) "+${fmt1(diff)}" else fmt1(diff),
                                color = if (diff < 0) cGo else cBad,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
                HorizontalDivider(color = cRule)
            }
            }

            if (problems.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                problems.forEach { problem ->
                    Text(
                        problem.message,
                        color = if (problem.severity == "BLOCK") cBad else cWarn,
                        fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            lastError?.let {
                Spacer(Modifier.height(12.dp))
                Text("ESPN said: $it", color = cBad, fontSize = 12.sp, lineHeight = 16.sp)
            }

            if (chosen != null && !blocked) {
                Spacer(Modifier.height(18.dp))
                if (!confirming) {
                    Button(
                        onClick = { confirming = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cAccent, contentColor = cOn),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Review this swap") }
                } else {
                    Text("This will send:", color = cFaint, fontSize = 10.sp)
                    moves.forEach { (id, from, to) ->
                        val who = team.roster.firstOrNull { it.playerId == id }?.name
                            ?: "player $id"
                        Text(
                            "$who: ${Enums.slot(from)} to ${Enums.slot(to)}",
                            color = cText, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApply(moves) },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cGo, contentColor = cOn),
                            modifier = Modifier.weight(2f)
                        ) { Text(if (busy) "Sending..." else "Confirm on ESPN") }
                        TextButton(
                            onClick = { confirming = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Back", color = cDim) }
                    }
                }
            }
        }
    }
}

/**
 * Every legal swap for this player, starters first.
 *
 * Locked players are listed but not selectable — hiding them would leave you
 * wondering where someone went, which is worse than showing why he can't move.
 */
private fun buildSwapOptions(
    player: RosterPlayer,
    team: com.aviato.fantasybrief.data.FantasyTeam,
    brief: Brief
): List<SwapOption> {
    val settings = brief.league.settings
    val pro = brief.proTeams
    val week = settings.scoringPeriodId
    val out = mutableListOf<SwapOption>()

    team.roster.filter { it.playerId != player.playerId }.forEach { other ->
        // Both directions must be legal — the transaction moves both players.
        val canTakeTheirs = player.eligibleSlots.contains(other.lineupSlotId)
        val canTakeMine = other.eligibleSlots.contains(player.lineupSlotId)
        if (!canTakeTheirs || !canTakeMine) return@forEach

        val locked = pro.hasKickedOff(other.proTeamId, week) ||
            pro.hasKickedOff(player.proTeamId, week)
        val note = when {
            pro.hasKickedOff(other.proTeamId, week) -> "already played"
            pro.isOnBye(other.proTeamId, week) &&
                Enums.isStarterSlot(player.lineupSlotId) -> "on bye"
            !other.healthy -> other.injuryStatus
            else -> null
        }
        out.add(SwapOption(other, other.lineupSlotId, locked, note))
    }

    // Genuinely empty starting slots — rare, but a hole in the lineup.
    settings.starterSlots.forEach { rule ->
        val filled = team.roster.count { it.lineupSlotId == rule.slotId }
        if (filled < rule.count &&
            player.eligibleSlots.contains(rule.slotId) &&
            rule.slotId != player.lineupSlotId
        ) {
            out.add(SwapOption(null, rule.slotId, false, "unfilled slot"))
        }
    }

    return out.sortedWith(
        compareBy<SwapOption> { it.locked }
            .thenBy { !Enums.isStarterSlot(it.targetSlot) }
            .thenByDescending { it.other?.projection ?: 0.0 }
    )
}
