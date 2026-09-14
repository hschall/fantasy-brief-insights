package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aviato.fantasybrief.data.CalendarDay
import com.aviato.fantasybrief.data.CalendarEntry
import com.aviato.fantasybrief.data.CalendarSlot
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.GameCalendar
import com.aviato.fantasybrief.data.LiveScoreboard
import com.aviato.fantasybrief.data.ProTeamIndex
import com.aviato.fantasybrief.data.RosterPlayer
import java.util.Locale

/**
 * When each of this team's players actually plays, in Mexico City time.
 *
 * The roster view answers "who is in my lineup". This answers "what is left
 * to happen", which is the question you have on a Sunday afternoon and the
 * one the app previously could not answer without opening the NFL app.
 */
@Composable
fun CalendarPane(
    roster: List<RosterPlayer>,
    pro: ProTeamIndex,
    week: Int,
    ownerTeamId: Int,
    live: LiveScoreboard? = null,
    /** Frozen week, when looking at a completed one. Null for the current week. */
    archive: com.aviato.fantasybrief.data.WeekRoster? = null,
    bottomInset: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    onPlayer: (RosterPlayer) -> Unit = {}
) {
    // One clock read for the whole screen. Two reads a millisecond apart can
    // disagree about whether a game has started, and the summary line
    // contradicting the rows below it is the kind of bug nobody reports.
    val now = remember(week, roster) { System.currentTimeMillis() }
    val cal = remember(roster, week, now) { GameCalendar.build(roster, pro, week, now) }

    /**
     * What he actually scored, or null.
     *
     * Null means two different things on purpose: he has not played yet, or
     * he has played and we do not have the number. Neither is a licence to
     * fall back to the projection — a projection rendered where an actual
     * belongs is a wrong number that looks current. The row shows a dash.
     *
     * weeklyActuals is the fallback for a past week, when the live scoreboard
     * covers the current period only.
     */
    fun actualOf(e: CalendarEntry): Double? =
        if (!e.kickedOff) null
        else live?.points(ownerTeamId, e.player.playerId)
            ?: e.player.weeklyActuals[week]

    /**
     * The projection he carried INTO that week.
     *
     * RosterPlayer.projection is always scoped to the current scoring period,
     * so on a past week it is a number from a different week entirely — and
     * colouring an old actual against it would be confidently wrong. The
     * archive froze the real one.
     */
    fun projOf(e: CalendarEntry): Double? =
        archive?.players?.firstOrNull { it.playerId == e.player.playerId }?.projection
            ?: e.player.projection

    val banked = cal.entries
        .filter { it.player.isStarter && it.kickedOff }
        .sumOf { actualOf(it) ?: 0.0 }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().background(Ink.ground),
        contentPadding = PaddingValues(bottom = bottomInset + 28.dp)
    ) {
        item {
            CalendarSummary(cal.startersLeft, cal.startersTotal, cal.pointsLive, banked)
        }

        if (cal.days.isEmpty()) {
            item {
                Text(
                    "No games found for week $week.",
                    style = inkBody(12.0, Ink.mid),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
        }

        cal.days.forEach { day ->
            item(key = "day-${day.sortKey}") { DayHeader(day) }
            day.slots.forEach { slot ->
                item(key = "slot-${day.sortKey}-${slot.millis ?: -1L}") {
                    SlotHeader(slot)
                }
                items(slot.entries.size) { i ->
                    val entry = slot.entries[i]
                    CalendarRow(entry, actualOf(entry), projOf(entry)) {
                        onPlayer(entry.player)
                    }
                }
            }
        }

        if (cal.byes.isNotEmpty()) {
            item { PaneSection("ON BYE", "${cal.byes.size} PLAYERS") }
            items(cal.byes.size) { i -> ByePlayerRow(cal.byes[i]) }
        }
    }
}

/** Local copy: TeamSection is private to RosterScreen.kt. */
@Composable
private fun PaneSection(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = inkLabel(11.0, Ink.paper))
        if (trailing != null) Text(trailing, style = inkLabel(9.0, Ink.mid))
    }
}

@Composable
private fun CalendarSummary(left: Int, total: Int, points: Double, banked: Double) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (total == 0) "\u2014" else "$left",
                style = inkNum(26.0, if (left == 0) Ink.mid else Ink.paper)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "OF $total STARTERS LEFT TO PLAY",
                style = inkLabel(10.0, Ink.mid),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                total == 0 -> "Nothing scheduled."
                left == 0 -> String.format(Locale.US, "%.1f banked. Everything is in.", banked)
                banked == 0.0 -> String.format(
                    Locale.US, "%.1f projected points still to come", points)
                else -> String.format(
                    Locale.US, "%.1f banked \u00B7 %.1f still to come", banked, points)
            },
            style = inkBody(12.0, Ink.mid)
        )
        Spacer(Modifier.height(8.dp))
        // Labelled, always. This screen deliberately does not follow the
        // handset, so it has to say which clock it is using.
        Text("ALL TIMES ${GameCalendar.ZONE_LABEL}", style = inkLabel(9.0, Ink.accent))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
}

@Composable
private fun DayHeader(day: CalendarDay) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(day.label, style = inkLabel(11.0, Ink.paper))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
        Spacer(Modifier.width(10.dp))
        val n = day.slots.sumOf { it.entries.size }
        Text("$n", style = inkNum(11.0, Ink.mid))
    }
}

@Composable
private fun SlotHeader(slot: CalendarSlot) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            slot.timeLabel.uppercase(Locale.US),
            style = inkLabel(10.0, if (slot.kickedOff) Ink.mid else Ink.accent)
        )
        if (slot.kickedOff) {
            Spacer(Modifier.width(8.dp))
            Text("STARTED", style = inkLabel(9.0, Ink.mid))
        }
        if (slot.starters > 0) {
            Spacer(Modifier.weight(1f))
            Text(
                "${slot.starters} STARTER${if (slot.starters == 1) "" else "S"}",
                style = inkLabel(9.0, Ink.mid)
            )
        }
    }
}

@Composable
private fun CalendarRow(
    entry: CalendarEntry,
    actual: Double?,
    projection: Double?,
    onClick: () -> Unit
) {
    val p = entry.player
    val starter = p.isStarter
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
            // A player whose game is over is history, not a decision — but
            // his score now carries information, so this is a demotion rather
            // than the near-erasure it was when the number was only a guess.
            .alpha(if (entry.kickedOff) 0.72f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Slot, not position: FLEX is the thing you might still change.
        Text(
            if (starter) Enums.slot(p.lineupSlotId) else "BE",
            style = inkLabel(9.0, if (starter) Ink.accent else Ink.mid),
            modifier = Modifier.width(38.dp)
        )
        Text(
            p.name,
            style = inkBody(13.0, if (starter) Ink.paper else Ink.mid),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!p.healthy) {
            Text(
                Enums.injuryShort(p.injuryStatus),
                style = inkLabel(9.0, Ink.negative),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(
            entry.opponent ?: "\u2014",
            style = inkBody(11.0, Ink.mid),
            modifier = Modifier.width(58.dp)
        )
        // Actual once his game is under way, projection before. Ink's
        // positive/negative mean exactly one thing — actual against
        // projection — which is precisely this comparison.
        val shown = if (entry.kickedOff) actual else projection
        val tone = when {
            !entry.kickedOff || actual == null -> if (starter) Ink.paper else Ink.mid
            projection == null -> if (starter) Ink.paper else Ink.mid
            actual >= projection -> Ink.positive
            else -> Ink.negative
        }
        SplitTotal(shown, big = 13.0, small = 9.0, color = tone,
            modifier = Modifier.width(46.dp))
    }
}

@Composable
private fun ByePlayerRow(p: RosterPlayer) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (p.isStarter) Enums.slot(p.lineupSlotId) else "BE",
                style = inkLabel(9.0, if (p.isStarter) Ink.negative else Ink.mid),
                modifier = Modifier.width(38.dp)
            )
            Text(
                p.name,
                style = inkBody(13.0, if (p.isStarter) Ink.paper else Ink.mid),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(p.proTeam, style = inkBody(11.0, Ink.mid))
    }
}
