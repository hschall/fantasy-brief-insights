package com.aviato.fantasybrief.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.LeagueRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaguePicker(
    leagues: List<LeagueRef>,
    active: LeagueRef?,
    defaultSeason: Int,
    onSelect: (LeagueRef) -> Unit,
    onAdd: (Long, Int) -> Unit,
    onRemove: (LeagueRef) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adding by rememberSaveable { mutableStateOf(leagues.isEmpty()) }
    var idText by rememberSaveable { mutableStateOf("") }
    var seasonText by rememberSaveable { mutableStateOf(defaultSeason.toString()) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Fb.Raised
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
            Text("Leagues", color = Fb.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Each league keeps its own history, snapshots and alerts. " +
                    "Switching never mixes them.",
                color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            leagues.forEach { ref ->
                val isActive = ref.key == active?.key
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (isActive) Fb.Teal.copy(alpha = 0.10f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { onSelect(ref); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ref.name,
                                    color = if (isActive) Fb.Teal else Fb.Ink,
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium
                                )
                                if (isActive) {
                                    Spacer(Modifier.width(8.dp))
                                    TagPill("active", Fb.Teal)
                                }
                            }
                            MetaRow(
                                (ref.teamName ?: "team not yet detected") to Fb.Muted,
                                "${ref.season}" to Fb.Faint,
                                "id ${ref.leagueId}" to Fb.Faint
                            )
                        }
                        TextButton(onClick = {
                            confirmRemove = if (confirmRemove == ref.key) null else ref.key
                        }) {
                            Text(
                                if (confirmRemove == ref.key) "Cancel" else "Remove",
                                color = Fb.Faint, fontSize = 11.sp
                            )
                        }
                    }
                    if (confirmRemove == ref.key) {
                        Row(
                            Modifier.padding(start = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Removes it from the list. Stored history stays on " +
                                    "disk and returns if you add it back.",
                                color = Fb.Muted, fontSize = 10.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onRemove(ref); confirmRemove = null }) {
                                Text("Remove", color = Fb.Red, fontSize = 12.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(color = Fb.Rule)
            }

            Spacer(Modifier.height(16.dp))

            if (!adding) {
                TextButton(onClick = { adding = true }) {
                    Text("Add a league", color = Fb.Teal, fontSize = 14.sp)
                }
            } else {
                Text("Add a league", color = Fb.Ink, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text(
                    "Open the league in ESPN and copy the number after leagueId= " +
                        "in the URL.",
                    color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = idText,
                        onValueChange = { idText = it.filter(Char::isDigit) },
                        label = { Text("League id") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = seasonText,
                        onValueChange = { seasonText = it.filter(Char::isDigit) },
                        label = { Text("Season") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val id = idText.toLongOrNull()
                        val yr = seasonText.toIntOrNull()
                        if (id != null && yr != null) {
                            onAdd(id, yr); adding = false; idText = ""; onDismiss()
                        }
                    },
                    enabled = idText.length > 5 && seasonText.length == 4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Fb.Teal, contentColor = Fb.Navy),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add and switch to it") }
                Text(
                    "The name and your team are filled in automatically on the " +
                        "first successful load.",
                    color = Fb.Faint, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
