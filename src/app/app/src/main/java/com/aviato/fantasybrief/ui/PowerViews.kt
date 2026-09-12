package com.aviato.fantasybrief.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.PowerSeries
import com.aviato.fantasybrief.data.StandingRow

/**
 * Two-week scoring average per team, plotted across the season.
 *
 * Hidden entirely below three weeks of data rather than drawn as a two-point
 * line, and missing weeks are never interpolated — a team that did not play
 * simply has no point there.
 */
@Composable
fun PowerChart(
    series: Map<Int, PowerSeries>,
    highlightTeamId: Int,
    highlightName: String,
    isMine: Boolean
) {
    val all = series.values.filter { it.points.isNotEmpty() }
    if (all.isEmpty()) return

    val weeks = all.flatMap { s -> s.points.map { it.first } }.distinct().sorted()
    val values = all.flatMap { s -> s.points.map { it.second } }
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).coerceAtLeast(1.0)
    val highlight = series[highlightTeamId]
    // positive may never draw a rival — it means "yours" here, not "good".
    val highlightColor = if (isMine) Ink.positive else Ink.accent

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.accent.copy(alpha = 0.05f))
            .border(1.dp, Ink.border, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                highlightName + (highlight?.points?.lastOrNull()
                    ?.let { "  \u00B7  power ${fmt1(it.second)}" } ?: ""),
                style = inkBody(13.0, Ink.paper), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text("${fmt1(lo)}\u2013${fmt1(hi)}", style = inkNum(10.0, Ink.mid))
        }

        Spacer(Modifier.height(10.dp))

        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            fun px(week: Int, value: Double): Offset {
                val x = if (weeks.size <= 1) size.width / 2f
                        else weeks.indexOf(week).toFloat() / (weeks.size - 1) * size.width
                val y = size.height -
                    ((value - lo) / span).toFloat() * size.height * 0.9f - size.height * 0.05f
                return Offset(x, y)
            }
            fun line(s: PowerSeries): Path? {
                if (s.points.size < 2) return null
                val path = Path()
                s.points.forEachIndexed { i, (w, v) ->
                    val o = px(w, v)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                return path
            }
            all.filter { it.teamId != highlightTeamId }.forEach { s ->
                line(s)?.let {
                    drawPath(it, Ink.mid.copy(alpha = 0.28f), style = Stroke(width = 1f))
                }
            }
            highlight?.let { s ->
                line(s)?.let { drawPath(it, highlightColor, style = Stroke(width = 2f)) }
                s.points.lastOrNull()?.let { (w, v) ->
                    drawCircle(highlightColor, radius = 3.5f, center = px(w, v))
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            weeks.forEachIndexed { i, w ->
                Text(
                    "W$w", style = inkNum(9.0, Ink.mid),
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        weeks.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    }
                )
            }
        }
    }
}

@Composable
fun StandingsTable(
    rows: List<StandingRow>,
    myTeamId: Int?,
    highlightTeamId: Int,
    hasResults: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onHighlight: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Spacer(Modifier.width(30.dp))
            Spacer(Modifier.weight(1f))
            Text(if (hasResults) "W-L" else "PROJ", style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
            Text("PF", style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
            Text("PWR", style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
            Text("6H", style = inkLabel(9.0, Ink.mid),
                modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        }

        // Collapsed to my row plus the two around it — enough to see where
        // I sit without ten rows of table on the most-used screen.
        val myIndex = rows.indexOfFirst { it.team.id == myTeamId }
        val shown = if (expanded || myIndex < 0) rows
                    else rows.filterIndexed { i, _ ->
                        kotlin.math.abs(i - myIndex) <= 1
                    }
        shown.forEach { r ->
            val mine = r.team.id == myTeamId
            val lit = r.team.id == highlightTeamId
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (lit) Ink.tileFill else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onHighlight(r.team.id) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(String.format(java.util.Locale.US, "%02d", r.rank),
                    style = inkNum(11.0, Ink.mid), modifier = Modifier.width(26.dp))
                Text(
                    r.team.name,
                    style = inkBody(14.0, if (mine) Ink.paper else Ink.mid),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(if (hasResults) r.record else "#${r.team.projectedRank}",
                    style = inkNum(11.5, Ink.mid),
                    modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
                Text(if (r.pointsFor > 0) fmt0(r.pointsFor) else "\u2014",
                    style = inkNum(11.5, Ink.mid),
                    modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
                Text(r.power?.let { fmt1(it) } ?: "\u2014",
                    style = inkNum(13.0, Ink.paper),
                    modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
                // Null, not zero — "no baseline" and "unchanged" are different.
                Text(
                    r.powerDelta?.let {
                        (if (it >= 0) "\u25B2" else "\u25BC") + fmt1(kotlin.math.abs(it))
                    } ?: "\u2014",
                    style = inkNum(11.5,
                        when {
                            r.powerDelta == null -> Ink.mid
                            r.powerDelta >= 0 -> Ink.positive
                            else -> Ink.negative
                        }),
                    modifier = Modifier.width(48.dp), textAlign = TextAlign.End
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
        }

        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                if (expanded) "SHOW LESS"
                else "ALL ${rows.size} TEAMS  \u25BE",
                style = inkLabel(9.5, Ink.accent)
            )
        }
    }
}

fun fmt0(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)
