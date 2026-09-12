package com.aviato.fantasybrief.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.AlertStore
import com.aviato.fantasybrief.data.BriefWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.aviato.fantasybrief.data.SecretStore
import com.aviato.fantasybrief.data.InsightsApi

@Composable
fun SettingsScreen(
    leagueId: Long,
    season: Int,
    onFullRefresh: () -> Unit = {},
    fullRefreshBusy: Boolean = false,
    onSignOut: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenScorecard: () -> Unit = {},
    onClose: () -> Unit = {},
    bottomInset: Dp = 0.dp
) {
    val context = LocalContext.current
    val store = remember { AlertStore(context) }
    val secrets = remember { SecretStore(context) }
    val scope = rememberCoroutineScope()

    var apiUrl by remember { mutableStateOf(secrets.apiUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(secrets.apiKey.orEmpty()) }
    var apiStatus by remember { mutableStateOf("") }

    var enabled by remember { mutableStateOf(store.enabled) }
    var hours by remember { mutableIntStateOf(store.pollHours) }
    var muted by remember { mutableStateOf(store.isMuted(leagueId)) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            store.enabled = true
            enabled = true
            BriefWorker.schedule(context, leagueId, season, hours)
        }
    }

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            store.enabled = true
            enabled = true
            BriefWorker.schedule(context, leagueId, season, hours)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Masthead(
            title = "Settings",
            subtitle = "Background checks and diagnostics",
            action = "Done" to onClose
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(PaddingValues(bottom = bottomInset))
        ) {
            Text(
                "Background checks apply to the active league only. Each league " +
                    "is muted and scheduled separately.",
                color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )

            SectionHeader("Background checks")

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check for changes", color = Fb.Ink, fontSize = 15.sp)
                    Text(
                        "Alerts you when a player switches NFL teams, an ownership " +
                            "percentage jumps, or someone on your roster gets a new " +
                            "injury tag.",
                        color = Fb.Muted, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (want) turnOn() else {
                            store.enabled = false; enabled = false
                            BriefWorker.cancel(context)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Fb.Teal)
                )
            }

            if (enabled) {
                Text(
                    "How often", color = Fb.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(3, 6, 12).forEach { h ->
                        FilterChip(
                            selected = hours == h,
                            onClick = {
                                hours = h; store.pollHours = h
                                BriefWorker.schedule(context, leagueId, season, h)
                            },
                            label = { Text("${h}h", fontSize = 12.sp) }
                        )
                    }
                }
                Text(
                    "NFL news does not move faster than this, and shorter intervals " +
                        "cost battery for nothing.",
                    color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mute this league", color = Fb.Ink, fontSize = 15.sp)
                    Switch(
                        checked = muted,
                        onCheckedChange = { muted = it; store.setMuted(leagueId, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Fb.Amber)
                    )
                }

                SectionHeader("Last check")
                Text(
                    if (store.lastRunMillis == 0L) "Has not run yet."
                    else SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
                        .format(Date(store.lastRunMillis)) + " — " + store.lastRunNote,
                    color = Fb.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                TextButton(
                    onClick = { BriefWorker.runNow(context, leagueId, season) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("Check now", color = Fb.Teal) }
            }

            SectionHeader("Accuracy")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Every recommendation is recorded and scored once its week " +
                        "completes, whether or not you acted on it.",
                    color = Fb.Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                OutlinedButton(
                    onClick = onOpenScorecard,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Scorecard", color = Fb.Teal, fontSize = 13.sp) }
            }

            SectionHeader("Data")
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Rosters, ownership and transactions are fetched every time " +
                        "you refresh. Slower-moving data is cached: NFL fixtures " +
                        "and byes for 7 days, depth charts for 24 hours, the " +
                        "matchup schedule for 12 hours.",
                    color = Fb.Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Text(
                    "Use this when something changed sooner than that — a " +
                        "mid-week depth chart move, for instance.",
                    color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp
                )
                OutlinedButton(
                    onClick = onFullRefresh,
                    enabled = !fullRefreshBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        if (fullRefreshBusy) "Refetching everything..."
                        else "Refresh everything",
                        color = Fb.Teal, fontSize = 13.sp
                    )
                }
            }

            SectionHeader("Analysis endpoint")
            Text(
                "Where the app uploads its brief and collects the analysis " +
                    "written against it.",
                color = Fb.Faint, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("Endpoint URL", fontSize = 11.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key", fontSize = 11.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    secrets.saveApi(apiUrl, apiKey)
                    apiStatus = "Saved"
                }) { Text("Save", color = Fb.Teal, fontSize = 13.sp) }

                TextButton(onClick = {
                    // Prove it works here rather than discovering a typo later
                    // when an upload fails silently in the background.
                    secrets.saveApi(apiUrl, apiKey)
                    apiStatus = "Testing..."
                    scope.launch {
                        val api = InsightsApi(secrets)
                        val ok = withContext(Dispatchers.IO) {
                            api.fetchInsights(leagueId) != null
                        }
                        apiStatus = if (ok) "Endpoint reachable"
                                    else api.lastError ?: "no response"
                    }
                }) { Text("Test", color = Fb.Muted, fontSize = 13.sp) }
            }
            if (apiStatus.isNotBlank()) {
                Text(
                    apiStatus, color = Fb.Faint, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            SectionHeader("Diagnostics")
            TextButton(
                onClick = { store.clear() },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text("Forget what's already been announced", color = Fb.Muted, fontSize = 13.sp)
            }
            TextButton(
                onClick = onOpenProbe,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text("ESPN endpoint probe", color = Fb.Muted, fontSize = 13.sp) }
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text("Sign out", color = Fb.Red, fontSize = 13.sp) }

            Column(Modifier.padding(24.dp)) {}
        }
    }
}
