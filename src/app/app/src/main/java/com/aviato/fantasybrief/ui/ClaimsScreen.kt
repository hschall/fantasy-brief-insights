package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.ScheduledAdd
import com.aviato.fantasybrief.data.WaiverClock

/**
 * Everything in flight: claims queued with ESPN, and adds this app has
 * scheduled for a waiver clear.
 *
 * A scheduled write you cannot see or cancel is worse than no scheduled write,
 * so this shows the computed fire time explicitly — a wrong derivation should
 * be visible here rather than discovered when it fails.
 */
@Composable
fun ClaimsScreen(
    brief: Brief?,
    scheduled: List<ScheduledAdd>,
    bottomInset: Dp = 0.dp,
    onCancel: (ScheduledAdd) -> Unit,
    onBack: () -> Unit
) {
    val b = brief
    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        Masthead(
            title = "In flight",
            subtitle = "Waiver claims with ESPN, and adds scheduled by this app",
            action = "Back" to onBack
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp)
        ) {
            val pending = scheduled.filter { it.status == "PENDING" }
            val settled = scheduled.filterNot { it.status == "PENDING" }

            item { ClaimSection("SCHEDULED ADDS", "NO PRIORITY SPENT") }
            if (pending.isEmpty()) {
                item {
                    Text(
                        "Nothing scheduled. Long-press a player on waivers to " +
                            "queue an add for when he clears.",
                        style = inkBody(12.0, Ink.mid), lineHeight = 17.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(pending, key = { "p-${it.id}" }) { item ->
                ScheduledRow(item, onCancel)
            }

            item { ClaimSection("WAIVER CLAIMS", "PRIORITY SPENT") }
            val claims = b?.pending.orEmpty()
            if (claims.isEmpty()) {
                item {
                    Text(
                        "No claims queued with ESPN. Claims only appear here " +
                            "between submitting one and the waiver run.",
                        style = inkBody(12.0, Ink.mid), lineHeight = 17.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(claims, key = { "c-${it.playerId}" }) { claim ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            b?.playerNames?.get(claim.playerId) ?: "player ${claim.playerId}",
                            style = inkBody(14.0, Ink.paper)
                        )
                        Text(
                            "${claim.transactionIds.size} pending transaction(s)",
                            style = inkBody(10.5, Ink.mid)
                        )
                    }
                    Text("QUEUED", style = inkLabel(9.0, Ink.accent))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
            }

            if (settled.isNotEmpty()) {
                item { ClaimSection("FINISHED", null) }
                items(settled, key = { "s-${it.id}" }) { item ->
                    ScheduledRow(item, onCancel)
                }
            }
        }
    }
}

@Composable
private fun ScheduledRow(item: ScheduledAdd, onCancel: (ScheduledAdd) -> Unit) {
    val tint = when (item.status) {
        "PENDING" -> Ink.accent
        "DONE" -> Ink.positive
        "LOST" -> Ink.negative
        else -> Ink.mid
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (item.status == "PENDING") Ink.tileFill else Color.Transparent)
            .border(1.dp, if (item.status == "PENDING") Ink.border else Ink.border,
                RoundedCornerShape(9.dp))
            .padding(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Add ${item.addPlayerName}", style = inkBody(14.0, Ink.paper))
                Text("drop ${item.dropPlayerName}", style = inkBody(11.0, Ink.mid))
            }
            Text(item.status, style = inkLabel(9.0, tint))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (item.status == "PENDING")
                "Fires ${WaiverClock.describe(item.firesAtMillis)}"
            else item.note.ifBlank { "Finished" },
            style = inkBody(11.0, if (item.status == "PENDING") Ink.accent else Ink.mid)
        )
        if (item.status == "PENDING") {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Ink.negative, RoundedCornerShape(6.dp))
                    .clickable { onCancel(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("CANCEL", style = inkLabel(10.0, Ink.negative)) }
        }
    }
}

@Composable
private fun ClaimSection(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = inkLabel(11.0, Ink.accent))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = inkLabel(9.0, Ink.mid))
        }
    }
}
