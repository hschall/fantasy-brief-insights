package com.aviato.fantasybrief.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Enums
import com.aviato.fantasybrief.data.ReplacementLevel

/**
 * Which median a lineup slot is judged against.
 *
 * FLEX has no median of its own, so it borrows WR's — the deepest position
 * most flexes draw from. Read from the slot id rather than the player's
 * position so two RBs in a flex are still judged as flex.
 */
fun medianPositionFor(slotId: Int, playerPosition: String): String = when (slotId) {
    3, 23, 5, 7 -> "WR"
    else -> playerPosition
}

/**
 * Projection against this league's median starter at that position.
 *
 * A FIXED ceiling would make every QB bar look maxed and every TE bar look
 * weak — a QB median of 17.6 against a TE median of 10.3 is not a difference
 * in quality. Scaling per position against median x 1.6 keeps the bar readable
 * as a judgement, which is the only question it is meant to answer. Raw points
 * are already on the row.
 */
@Composable
fun MedianBar(
    projection: Double?,
    median: Double?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        val trackY = size.height / 2f
        val track = 4f
        drawRect(
            Color(0x297FB0E0), Offset(0f, trackY - track / 2),
            Size(size.width, track)
        )
        if (projection == null) return@Canvas

        val ceiling = ((median ?: 0.0) * 1.6).coerceAtLeast(6.0)
        val fill = (projection / ceiling).toFloat().coerceIn(0f, 1f)
        // Below-median is NOT red. Red on this screen means a player is not
        // playing — a bye or an OUT — and a merely-below-average starter is a
        // different kind of fact. Dimmed accent says "under the line" without
        // claiming something is broken.
        val color = when {
            median == null -> Ink.mid
            projection >= median -> Ink.positive
            else -> Ink.accent.copy(alpha = 0.55f)
        }
        drawRect(color, Offset(0f, trackY - track / 2), Size(size.width * fill, track))

        // The tick is a mark, not a segment boundary — taller than the track.
        median?.let { m ->
            val x = (m / ceiling).toFloat().coerceIn(0f, 1f) * size.width
            drawRect(
                Ink.paper.copy(alpha = 0.55f),
                Offset(x - 0.5f, trackY - track / 2 - 2f),
                Size(1f, track + 4f)
            )
        }
    }
}

/** 26px framed abbreviation. Real logos drop into this exact box later. */
@Composable
fun TeamMarkBox(abbrev: String) {
    ProTeamLogo(abbrev, 26.dp, Ink.accent)
}

@Composable
private fun TeamMarkBoxFramed(abbrev: String) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .border(1.dp, Ink.border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) { Text(abbrev, style = inkLabel(8.5, Ink.accent)) }
}

/**
 * Bye exposure. A count of players is not actionable; a count of HOLES is, so
 * the sentence beneath names how many have no eligible replacement.
 */
/** week, starters out, bench out, uncovered starting slots. */
data class ByeWeek(
    val week: Int,
    val startersOut: Int,
    val benchOut: Int,
    val holes: Int
) {
    val total: Int get() = startersOut + benchOut
}

/**
 * Severity is driven by STARTERS ONLY.
 *
 * Five benched players on bye costs nothing; five starters is a lost week. A
 * combined count treats those identically, which is the opposite of useful —
 * so the big number is starters and the bench count sits beneath it.
 */
@Composable
fun ByeRow(
    weeks: List<ByeWeek>,
    currentWeek: Int,
    selectedWeek: Int = currentWeek,
    onSelect: (Int) -> Unit = {}
) {
    // Scrolls the whole remaining season. take(10) cut it off at week 10,
    // which hid exactly the late-season weeks worth planning for.
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(weeks.size) { idx ->
            val w = weeks[idx]
            val week = w.week
            val out = w.startersOut
            val tint = when {
                out >= 3 -> Ink.negative
                out > 0 -> Ink.paper
                else -> Ink.mid
            }
            Column(
                Modifier.width(46.dp).clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            week == selectedWeek -> Ink.accent.copy(alpha = 0.20f)
                            out >= 3 -> Ink.negative.copy(alpha = 0.16f)
                            out > 0 -> Ink.tileFill
                            else -> Color.Transparent
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            week == selectedWeek -> Ink.accent
                            week == currentWeek -> Ink.positive.copy(alpha = 0.6f)
                            out >= 3 -> Ink.negative
                            else -> Ink.border
                        },
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(week) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (out == 0) "\u2014" else out.toString(),
                    style = inkNum(15.0, tint))
                Text(
                    if (w.benchOut > 0) "+${w.benchOut}" else " ",
                    style = inkNum(8.5, Ink.mid)
                )
                Text("W$week", style = inkLabel(8.5,
                    when {
                        week == selectedWeek -> Ink.paper
                        week == currentWeek -> Ink.positive
                        else -> Ink.mid
                    }))
            }
        }
    }
}

@Composable
fun ReplacementRow(
    position: String,
    yours: Double?,
    median: Double,
    p25: Double,
    slots: Int
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(position, style = inkLabel(11.0, Ink.paper), modifier = Modifier.width(48.dp))
        Text(
            yours?.let { fmt1(it) } ?: "\u2014",
            style = inkNum(13.0, when {
                yours == null -> Ink.mid
                yours >= median -> Ink.positive
                else -> Ink.negative
            }),
            modifier = Modifier.weight(1f), textAlign = TextAlign.End
        )
        Text(fmt1(median), style = inkNum(13.0, Ink.paper),
            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(fmt1(p25), style = inkNum(12.0, Ink.mid),
            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(slots.toString(), style = inkNum(12.0, Ink.mid),
            modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}
