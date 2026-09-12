package com.aviato.fantasybrief.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import java.util.Locale

/**
 * Ink palette. Scoped to the Matchup screen.
 *
 * positive/negative mean EXACTLY ONE THING: actual against projection. They
 * never colour a name, a tier or a position. Anything that is not a comparison
 * against a projection is paper or mid. This deliberately overrides the
 * tier-colouring used elsewhere in the app.
 */
object Ink {
    val ground = Color(0xFF0B1320)
    val paper = Color(0xFFEEF3F8)
    val mid = Color(0xFF8BA0B5)
    val accent = Color(0xFF7FB0E0)
    val positive = Color(0xFF4FD0B0)
    val negative = Color(0xFFE78E88)
    val border = Color(0x2E7FB0E0)      // accent @ 20%
    val tileFill = Color(0x1A7FB0E0)    // accent @ 9%, PLAYED PLAYERS ONLY
    val barTrack = Color(0x2E7FB0E0)    // accent @ 18%

}


// Barlow / Barlow Condensed would be bundled TTFs. Until then: system sans,
// with tabular figures so numeric columns align — which is what the spec's
// font choice is actually protecting. One place to swap later.
private val Sans = FontFamily.SansSerif
private const val TNUM = "tnum"

fun inkLabel(size: Double = 10.0, color: Color = Ink.mid) = TextStyle(
    fontFamily = Sans, fontSize = size.sp, color = color,
    fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp
)

fun inkNum(size: Double, color: Color, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontFamily = Sans, fontSize = size.sp, color = color, fontWeight = weight,
    fontFeatureSettings = TNUM
)

fun inkBody(size: Double = 12.0, color: Color = Ink.paper) = TextStyle(
    fontFamily = Sans, fontSize = size.sp, color = color
)

/**
 * A total with its decimal demoted.
 *
 * "118.40" renders 118 large and .40 small in mid. Scanning a column of these
 * reads as a clean column of integers, which is the single biggest legibility
 * win on the screen — so it applies to every total, header and tile alike.
 */
@Composable
fun SplitTotal(
    value: Double?,
    big: Double,
    small: Double,
    color: Color,
    align: TextAlign = TextAlign.End,
    modifier: Modifier = Modifier
) {
    if (value == null) {
        Text("\u2014", style = inkNum(big * 0.62, Ink.mid), textAlign = align,
            modifier = modifier)
        return
    }
    val text = String.format(Locale.US, "%.1f", value)
    val dot = text.indexOf('.')
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = big.sp, color = color,
                fontWeight = FontWeight.SemiBold)) {
                append(text.substring(0, dot))
            }
            withStyle(
                SpanStyle(fontSize = small.sp, color = color.copy(alpha = 0.55f))
            ) {
                append(text.substring(dot))
            }
        },
        style = TextStyle(fontFamily = Sans, fontFeatureSettings = TNUM),
        textAlign = align,
        modifier = modifier
    )
}

/** Real minus sign, not a hyphen. */
fun signed(v: Double): String =
    if (v >= 0) String.format(Locale.US, "+%.2f", v)
    else String.format(Locale.US, "\u2212%.2f", -v)

fun signed1(v: Double): String =
    if (v >= 0) String.format(Locale.US, "+%.1f", v)
    else String.format(Locale.US, "\u2212%.1f", -v)

fun two(v: Double): String = String.format(Locale.US, "%.2f", v)

/**
 * Spine colour, delegating to the app-wide tier definition so this screen
 * cannot drift. Full intensity for all three — hue carries the standing.
 */
fun inkTierSpine(rank: String?): Color = tierColor(rank)
