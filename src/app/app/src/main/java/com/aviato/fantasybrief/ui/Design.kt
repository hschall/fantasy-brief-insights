package com.aviato.fantasybrief.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.PositionLevel
import java.util.Locale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

object Fb {
    val Navy = Color(0xFF0A1A2B)
    val Ground = Color(0xFF0B1320)
    val Card = Color(0xFF121C2B)
    val Raised = Color(0xFF16212F)
    val Rule = Color(0xFF22303C)

    val Teal = Color(0xFF1FB8A6)
    val Ink = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B9AA7)
    val Faint = Color(0xFF5A6C7A)

    val Red = Color(0xFFE0603A)     // act today
    val Amber = Color(0xFFD4A24C)   // plan ahead
    val Blue = Color(0xFF4A7FB5)    // act this week
    val Green = Color(0xFF3FA372)   // opportunity

    // TIER. Hue carries the standing; all three at full intensity so none
    // reads as "less important", only "different". Kept clear of Red and of
    // the Matchup screen's salmon negative so a tier can never be mistaken
    // for a performance signal.
    // Neon, and deliberately far from the waiver amber #E0873A so that
    // best-at-his-position never reads as costs-you-a-claim.
    val TierLegendary = Color(0xFFFF7900)
    /** Everything on a legendary card. Full white, no muted variants. */
    val LegendaryText = Color(0xFFFFFFFF)
    /**
     * Bright gold-orange, Fortnite-legendary. This is a LIGHT fill, so
     * anything drawn on it must use TierLegendaryOn — the app's paper text
     * sits at 2:1 against it and is unreadable.
     */
    /** A tint, like the other tiers — not an opaque fill. */
    val TierLegendaryFill = Color(0xFFFF7900)
    /** Text colour for anything on a legendary fill. 7.9:1. */
    val TierLegendaryOn = Color(0xFF000000)
    val TierElite = Color(0xFFD14BF0)   // magenta-violet
    val TierSolid = Color(0xFF2E8BFF)   // saturated blue, clear of the grey
    val TierBelow = Color(0xFF8BA0B5)   // grey
}

/**
 * The single source of truth for tier colour. Every screen calls this —
 * roster rows, wire tiers, the projection scale, the player sheet, the
 * matchup spine. One definition, no drift.
 */
/**
 * Background wash for a tier.
 *
 * LEGENDARY gets a real fill rather than a hint — it means both the
 * projection and the analysts put the player at the very top of his position,
 * which happens to about ten players a week. A tier that rare should be
 * visible from across the room; everything else stays a wash so the list
 * still reads as a list.
 */
/** Cards keep their own fill; legendary is marked by its border. */
fun tierFill(rank: String?, base: Color = Color.Transparent): Color = base

/** Border for a card. Full-strength orange on legendary, hairline otherwise. */
fun tierBorder(rank: String?, base: Color): Color =
    if (rank == "LEGENDARY") Fb.TierLegendary else base

fun tierBorderWidth(rank: String?) = 1

fun tierColor(rank: String?): Color = when (rank) {
    "LEGENDARY" -> Fb.TierLegendary
    "ELITE" -> Fb.TierElite
    "SOLID" -> Fb.TierSolid
    else -> Fb.TierBelow
}

/**
 * Outline padlock for a player in this week's lineup locks.
 *
 * Drawn rather than an emoji glyph: the system emoji font renders its own
 * colours and ignores the tint, so a "green lock" came out multicoloured and
 * a different weight on every device.
 */
@Composable
fun LockBadge(size: Dp = 13.dp, color: Color = Color(0xFF3DFF7A)) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.13f

        // Shackle: a half arc sitting on the body.
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.26f, h * 0.06f),
            size = Size(w * 0.48f, h * 0.46f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        // Body.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.42f),
            size = Size(w * 0.72f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(width = stroke)
        )
        // Keyhole.
        drawCircle(
            color = color,
            radius = w * 0.07f,
            center = Offset(w * 0.50f, h * 0.65f)
        )
    }
}

/** Numbers use monospace so a column can be scanned vertically. */
@Composable
fun Stat(
    value: String,
    size: Int = 15,
    color: Color = Fb.Ink,
    weight: FontWeight = FontWeight.Bold
) = Text(value, color = color, fontSize = size.sp, fontWeight = weight,
    fontFamily = FontFamily.Monospace)

@Composable
fun StatCell(value: String, color: Color = Fb.Ink, size: Int = 13) =
    Stat(value, size, color, FontWeight.Medium)

@Composable
fun Masthead(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    action: Pair<String, () -> Unit>? = null,
    onTitleClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null
) {
    Column {
        Row(
            Modifier.fillMaxWidth().background(Fb.Navy)
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = if (onTitleClick != null)
                        Modifier.clickable { onTitleClick() } else Modifier
                ) {
                    Text(title, color = Color.White, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold)
                    // Chevron signals the title is a control, not a label.
                    if (onTitleClick != null) {
                        Text("  \u25BE", color = Fb.Teal, fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    trailing?.let {
                        Text(
                            it, color = Fb.Teal, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)
                        )
                    }
                }
                subtitle?.let {
                    Text(it, color = Fb.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                }
            }
            onSettings?.let {
                Box(
                    Modifier.size(34.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { it() },
                    contentAlignment = Alignment.Center
                ) { Text("\u2699", color = Fb.Muted, fontSize = 17.sp) }
            }
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) {
                    Text(label, color = Fb.Teal, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Fb.Teal))
    }
}

/** Big scannable counts. The first thing the eye should land on. */
@Composable
fun StatStrip(items: List<Triple<String, String, Color>>) {
    Row(
        Modifier.fillMaxWidth().background(Fb.Card)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { (value, label, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = color, fontSize = 26.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(label, color = Fb.Faint, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(text, color = Fb.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        trailing?.let { Text(it, color = Fb.Faint, fontSize = 11.sp) }
    }
}

@Composable
fun TagPill(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        modifier = Modifier.clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun PriorityCard(tag: String, color: Color, body: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min).clip(RoundedCornerShape(5.dp))
            .background(Fb.Card)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp)) {
            TagPill(tag, color)
            Text(body, color = Fb.Ink, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * The signature element: where a projection sits against what this league
 * actually starts. Two ticks mark the SOLID and ELITE lines; the dot is the
 * player. Turns "is 12.1 good?" from arithmetic into a glance.
 */
@Composable
fun ProjectionScale(
    projection: Double?,
    level: PositionLevel?,
    modifier: Modifier = Modifier,
    barWidth: Dp = 86.dp
) {
    if (projection == null || level == null) {
        Box(modifier.width(barWidth).height(14.dp)); return
    }
    val max = maxOf(level.eliteLine * 1.35, projection * 1.08, 1.0)
    val dotColor = when {
        projection >= level.eliteLine -> Fb.TierElite
        projection >= level.solidLine -> Fb.TierSolid
        else -> Fb.TierBelow
    }

    Canvas(modifier.width(barWidth).height(14.dp)) {
        val y = size.height / 2f
        val track = 3f
        drawRect(Fb.Rule, Offset(0f, y - track / 2), Size(size.width, track))

        // Zone from SOLID to ELITE, shaded — "bench-worthy but not a starter".
        val solidX = (level.solidLine / max).toFloat() * size.width
        val eliteX = (level.eliteLine / max).toFloat() * size.width
        drawRect(Fb.TierSolid.copy(alpha = 0.30f), Offset(solidX, y - track / 2),
            Size((eliteX - solidX).coerceAtLeast(0f), track))
        drawRect(Fb.TierElite.copy(alpha = 0.35f), Offset(eliteX, y - track / 2),
            Size((size.width - eliteX).coerceAtLeast(0f), track))

        listOf(solidX, eliteX).forEach { x ->
            drawRect(Fb.Muted, Offset(x - 0.75f, y - 5f), Size(1.5f, 10f))
        }
        val dotX = (projection / max).toFloat().coerceIn(0f, 1f) * size.width
        drawCircle(dotColor, radius = 4.5f, center = Offset(dotX, y))
    }
}

@Composable
fun MetaRow(vararg parts: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        parts.forEach { (text, color) ->
            if (text.isNotBlank()) Text(text, color = color, fontSize = 10.sp)
        }
    }
}

@Composable
fun ByeChart(countsByWeek: List<Pair<Int, Int>>, currentWeek: Int) {
    if (countsByWeek.isEmpty()) return
    val max = (countsByWeek.maxOf { it.second }).coerceAtLeast(3)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(76.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        countsByWeek.forEach { (week, count) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (count > 0) {
                    Text(count.toString(),
                        color = if (count >= 3) Fb.Red else Fb.Muted,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Canvas(
                    Modifier.fillMaxWidth()
                        .height(((count.toFloat() / max) * 52).dp.coerceAtLeast(2.dp))
                ) {
                    drawRect(
                        when {
                            count >= 3 -> Fb.Red
                            count > 0 -> Fb.Blue
                            else -> Fb.Rule
                        },
                        Offset.Zero, Size(size.width, size.height)
                    )
                }
                Text(
                    week.toString(),
                    color = if (week == currentWeek) Fb.Teal else Fb.Faint,
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/**
 * A player's name coloured by where he stands in THIS league.
 *
 * Teal clears the median starter, blue clears the 25th percentile, muted
 * sits below it. Nulls stay muted rather than guessing — a kicker with no
 * published projection must not read as "bad".
 */
fun tierTextColor(rank: String?): Color =
    if (rank == null) Fb.Ink else tierColor(rank)

/**
 * Actual against projected, once the game has started.
 *
 * Before kickoff there is no verdict to render — a 0.0 that means "hasn't
 * played" must not look like a bad game, so it returns muted.
 */
fun paceColor(actual: Double?, projected: Double?, started: Boolean): Color = when {
    !started || actual == null || projected == null -> Fb.Faint
    projected <= 0.0 -> Fb.Ink
    actual >= projected -> Fb.Green
    actual >= projected * 0.6 -> Fb.Amber
    else -> Fb.Red
}

fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
fun fmtPct(v: Double): String = String.format(Locale.US, "%.0f%%", v)
fun fmtDelta(v: Double): String = String.format(Locale.US, "%+.1f", v)
