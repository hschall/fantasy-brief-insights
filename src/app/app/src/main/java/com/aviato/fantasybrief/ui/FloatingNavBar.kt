package com.aviato.fantasybrief.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding

enum class NavIcon { TODAY, MATCHUP, TEAM, WIRE }

/**
 * Floating pill navigation.
 *
 * The active item expands to carry its label; the rest are icons only. That
 * keeps four destinations legible on a phone without four permanent captions,
 * and the expansion animates so the change of tab is visible rather than
 * inferred.
 *
 * Icons are drawn rather than imported — four glyphs is not worth a Material
 * icons dependency, and hand-drawn shapes match the app's line weight.
 */
@Composable
fun FloatingNavBar(
    items: List<Triple<NavIcon, String, Int>>,   // icon, label, badge count
    selectedIndex: Int,
    bottomInset: Dp,
    onSelect: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Fb.Navy)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Fb.Rule))
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, (icon, label, badge) ->
                NavItem(
                    icon = icon,
                    label = label,
                    badge = badge,
                    selected = i == selectedIndex,
                    onClick = { onSelect(i) }
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: NavIcon,
    label: String,
    badge: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) Fb.Teal else Color.Transparent, label = "navBg"
    )
    val tint by animateColorAsState(
        if (selected) Fb.Navy else Fb.Faint, label = "navTint"
    )
    val interaction = remember { MutableInteractionSource() }

    // Fixed width, always labelled. The expanding pill made the whole bar
    // reflow on every tap — more movement than a tab change earns, and it
    // meant no item ever sat in the same place twice.
    Column(
        Modifier.width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                // No ripple — it fights the pill's own fill.
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Canvas(Modifier.size(18.dp)) { drawNavIcon(icon, tint) }
            if (badge > 0 && !selected) {
                Box(
                    Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                        .background(Fb.TierElite)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label, color = tint, fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** Simple geometry, legible at 19dp, consistent stroke weight. */
private fun DrawScope.drawNavIcon(icon: NavIcon, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.11f

    when (icon) {
        // A page with a rule across the top.
        NavIcon.TODAY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.12f, h * 0.10f),
                size = Size(w * 0.76f, h * 0.80f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
                style = Stroke(width = stroke)
            )
            drawLine(
                color, Offset(w * 0.12f, h * 0.36f), Offset(w * 0.88f, h * 0.36f),
                strokeWidth = stroke
            )
        }
        // Two arrows meeting: a head-to-head.
        NavIcon.MATCHUP -> {
            val left = Path().apply {
                moveTo(w * 0.10f, h * 0.22f)
                lineTo(w * 0.42f, h * 0.50f)
                lineTo(w * 0.10f, h * 0.78f)
            }
            val right = Path().apply {
                moveTo(w * 0.90f, h * 0.22f)
                lineTo(w * 0.58f, h * 0.50f)
                lineTo(w * 0.90f, h * 0.78f)
            }
            drawPath(left, color, style = Stroke(width = stroke))
            drawPath(right, color, style = Stroke(width = stroke))
        }
        // A roster: stacked bars, longest first.
        NavIcon.TEAM -> {
            listOf(0.22f to 0.80f, 0.46f to 0.62f, 0.70f to 0.44f).forEach { (y, len) ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * y),
                    size = Size(w * len, stroke * 1.15f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke)
                )
            }
        }
        // A plus: something to add.
        NavIcon.WIRE -> {
            drawCircle(color, radius = w * 0.40f, style = Stroke(width = stroke))
            drawLine(
                color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.70f),
                strokeWidth = stroke
            )
            drawLine(
                color, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.70f, h * 0.50f),
                strokeWidth = stroke
            )
        }
    }
}
