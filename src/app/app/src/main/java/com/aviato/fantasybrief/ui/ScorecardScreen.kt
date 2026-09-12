package com.aviato.fantasybrief.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.Scorecard
import com.aviato.fantasybrief.data.ScorecardExport
import com.aviato.fantasybrief.data.Verdict

/**
 * Was the app right, and did ignoring it cost anything?
 *
 * Deliberately blunt. A tool that recommends things without ever being scored
 * is an opinion generator, and the whole point of this screen is to find out
 * which of its signals are real.
 */
@Composable
fun ScorecardScreen(brief: Brief?, bottomInset: Dp = 0.dp, onBack: () -> Unit) {
    var expandedKind by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val b = brief

    Column(Modifier.fillMaxSize().background(Ink.ground)) {
        Masthead(
            title = "Scorecard",
            subtitle = "What this app claimed, and what actually happened",
            action = "Back" to onBack
        )

        if (b == null || b.scorecard.isEmpty()) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(10.dp)) {
                Text(
                    "Nothing settled yet.",
                    style = inkBody(15.0, Ink.paper)
                )
                Text(
                    "Recommendations are recorded on every refresh and scored " +
                        "once the week they were made in is complete. A week in " +
                        "progress is never scored — grading a player who has not " +
                        "taken the field would make this say whatever the " +
                        "schedule happened to be.",
                    style = inkBody(12.5, Ink.mid), lineHeight = 18.sp
                )
                b?.let { brief2 ->
                    Text(
                        "Currently week ${brief2.league.settings.currentMatchupPeriod}. " +
                            "The first verdicts arrive once that week finishes. " +
                            "${brief2.predictions.size} prediction(s) recorded so far.",
                        style = inkBody(12.0, Ink.accent)
                    )
                    Spacer(Modifier.height(14.dp))
                    ExportRow(brief2, copied) { copied = it }
                }
            }
            return@Column
        }

        val total = b.scorecard.sumOf { it.settled }
        val correct = b.scorecard.sumOf { it.correct }
        val left = b.scorecard.sumOf { it.leftOnTable }
        val saved = b.scorecard.sumOf { it.savedByIgnoring }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 28.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Headline(
                        "${(100.0 * correct / total).toInt()}%",
                        "$correct of $total right", Ink.paper
                    )
                    Headline(fmt1(left), "points left on the table", Ink.negative)
                    Headline(fmt1(saved), "saved by ignoring", Ink.positive)
                }
            }

            item {
                Text(
                    "\u201cLeft on the table\u201d is realised edge on calls you " +
                        "declined that turned out right. It is a prompt to look, " +
                        "not a verdict \u2014 declining a claim because you were " +
                        "saving waiver priority is a good decision that scores as " +
                        "a miss here.",
                    style = inkBody(11.0, Ink.mid), lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item { InkSectionRow2("BY SIGNAL", "TAP FOR DETAIL") }

            items(b.scorecard, key = { it.kind }) { s ->
                SignalRow(
                    s,
                    expanded = expandedKind == s.kind,
                    onToggle = {
                        expandedKind = if (expandedKind == s.kind) null else s.kind
                    },
                    verdicts = b.verdicts.filter { it.prediction.kind == s.kind }
                )
            }

            item {
                Column(Modifier.padding(16.dp)) { ExportRow(b, copied) { copied = it } }
            }

            item {
                Text(
                    "A signal that is directionally right but under-claims is " +
                        "still useful. One that is directionally wrong is not, " +
                        "however precise its numbers look.",
                    style = inkBody(11.0, Ink.mid), lineHeight = 16.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ExportRow(b: Brief, copied: Boolean, onCopied: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(2f).clip(RoundedCornerShape(6.dp))
                .border(1.dp, Ink.accent, RoundedCornerShape(6.dp))
                .clickable {
                    val text = ScorecardExport.build(b, b.predictions)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("scorecard", text))
                    onCopied(true)
                }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (copied) "COPIED" else "COPY FOR ANALYSIS",
                style = inkLabel(10.5, Ink.accent))
        }
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                .border(1.dp, Ink.mid, RoundedCornerShape(6.dp))
                .clickable {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ScorecardExport.build(b, b.predictions))
                    }
                    context.startActivity(Intent.createChooser(send, "Send scorecard"))
                }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) { Text("SHARE", style = inkLabel(10.5, Ink.mid)) }
    }
}

@Composable
private fun Headline(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = inkNum(26.0, color))
        Text(label, style = inkLabel(8.5, Ink.mid), textAlign = TextAlign.Center)
    }
}

@Composable
private fun SignalRow(
    s: Scorecard.Summary,
    expanded: Boolean,
    onToggle: () -> Unit,
    verdicts: List<Verdict>
) {
    val hit = s.hitRate
    // A coin flip is the bar. Below it the signal is worse than guessing.
    val tint = when {
        s.settled < 5 -> Ink.mid          // too few to judge
        hit >= 0.6 -> Ink.positive
        hit >= 0.5 -> Ink.accent
        else -> Ink.negative
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp)).background(Ink.tileFill)
            .border(1.dp, Ink.border, RoundedCornerShape(9.dp))
            .clickable { onToggle() }.padding(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.kind.replace('_', ' '), style = inkLabel(11.0, Ink.paper))
                Text(
                    "${s.settled} settled \u00B7 ${s.followed} followed",
                    style = inkBody(11.0, Ink.mid),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (s.settled < 5) "\u2014"
                    else "${(hit * 100).toInt()}%",
                    style = inkNum(19.0, tint)
                )
                Text(
                    if (s.settled < 5) "TOO FEW" else "HIT RATE",
                    style = inkLabel(8.0, Ink.mid)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "claimed ${signed1(s.meanEdgeClaimed)} \u00B7 " +
                    "delivered ${signed1(s.meanEdgeReal)}",
                style = inkBody(11.0, Ink.mid), modifier = Modifier.weight(1f)
            )
            Text(
                if (s.calibration >= 0) "under-claims" else "over-claims",
                style = inkLabel(9.0,
                    if (s.calibration >= 0) Ink.positive else Ink.negative)
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
            verdicts.sortedByDescending { it.pointsLeftOnTable }.take(10).forEach { v ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("W${v.prediction.week}", style = inkNum(9.5, Ink.mid),
                        modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.prediction.comparedToName?.let {
                                "${v.prediction.subjectName} over $it"
                            } ?: v.prediction.subjectName,
                            style = inkBody(12.0, Ink.paper)
                        )
                        Text(
                            "claimed ${signed1(v.prediction.claimedEdge)}, " +
                                "got ${signed1(v.realEdge)}" +
                                if (v.followed) " \u00B7 followed" else "",
                            style = inkBody(10.0, Ink.mid)
                        )
                    }
                    Text(
                        if (v.correct) "\u2713" else "\u2715",
                        style = inkNum(13.0,
                            if (v.correct) Ink.positive else Ink.negative)
                    )
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.leftOnTable > 0)
                    "${fmt1(s.leftOnTable)} points left on the table"
                else "Nothing forgone here",
                style = inkBody(10.5,
                    if (s.leftOnTable > 0) Ink.negative else Ink.mid)
            )
        }
    }
}

@Composable
private fun InkSectionRow2(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = inkLabel(11.5, Ink.accent))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Ink.border))
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = inkLabel(9.5, Ink.mid))
        }
    }
}
