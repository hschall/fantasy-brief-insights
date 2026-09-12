package com.aviato.fantasybrief.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic check for things worth interrupting you about.
 *
 * CRITICAL: this worker reads the snapshot but NEVER writes one. If it wrote,
 * every change would be absorbed silently in the background and the research
 * queue would be empty by the time you opened the app. Only a manual refresh
 * advances the baseline.
 */
class BriefWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val secrets = SecretStore(ctx)
        val alerts = AlertStore(ctx)

        if (!secrets.hasCredentials) {
            alerts.lastRunNote = "Skipped — not signed in"
            return@withContext Result.success()
        }

        val leagueId = inputData.getLong(KEY_LEAGUE, 0L)
        val season = inputData.getInt(KEY_SEASON, 0)
        if (leagueId == 0L || season == 0) return@withContext Result.failure()

        if (alerts.isMuted(leagueId)) {
            alerts.lastRunNote = "Muted"
            return@withContext Result.success()
        }

        val client = EspnClient(secrets)
        val observations = ObservationStore(ctx)
        val previous = observations.baseline(leagueId, season, 24L)

        val leagueOut = LeagueRepository(client, secrets).load(season, leagueId)
        if (leagueOut is LeagueRepository.Outcome.Failed) {
            alerts.lastRunNote = leagueOut.message
            alerts.lastRunMillis = System.currentTimeMillis()
            // 401 needs a human. Retrying on a schedule will not fix it.
            return@withContext if (leagueOut.code == 401) Result.success() else Result.retry()
        }
        val ok = leagueOut as LeagueRepository.Outcome.Ok

        val wire = when (
            val w = WireRepository(client)
                .load(season, leagueId, ok.league.settings.scoringPeriodId)
        ) {
            is WireRepository.Outcome.Ok -> w.players
            is WireRepository.Outcome.Failed -> emptyList()
        }

        // Transactions first — authoritative, and they fire once per real
        // event rather than once per observed state change.
        val events = EventStore(ctx)
        val activity = client.get(
            client.leagueUrl(season, leagueId) +
                "/communication/?view=kona_league_communication",
            ActivityLog.FILTER
        )
        val newTx = if (activity.ok) {
            runCatching {
                events.append(leagueId, season, ActivityLog.parse(activity.body))
            }.getOrElse { emptyList() }
        } else emptyList()

        events.rememberNames(leagueId, season, buildMap {
            wire.forEach { put(it.playerId, it.name) }
            ok.league.teams.forEach { t -> t.roster.forEach { put(it.playerId, it.name) } }
        })
        val names = events.loadNames(leagueId, season)

        // Gold stars first: an elite starter ruled out with a free backup
        // is the only thing here worth interrupting a day for.
        val depthForAlerts = DepthChartLoader.load(
            season, Enums.REAL_PRO_TEAMS, ResponseCache(ctx)
        ).first
        val atRiskPairs = AtRisk.find(
            ok.league, wire, depthForAlerts, ok.league.settings.scoringPeriodId
        )
        val replacementForAlerts = ReplacementLevel.from(ok.league)

        val found = AlertRules.goldStar(
            ok.league, atRiskPairs, replacementForAlerts
        ) +
            AlertRules.fromTransactions(ok.league, newTx, wire, names) +
            if (previous != null) {
                AlertRules.evaluate(ok.league, wire, ok.proTeams, previous)
            } else emptyList()
        val unseen = alerts.takeUnseen(found)

        unseen.forEach { notify(ctx, it) }

        alerts.lastRunMillis = System.currentTimeMillis()
        alerts.lastRunNote = when {
            unseen.isNotEmpty() -> "${unseen.size} sent"
            found.isNotEmpty() -> "${found.size} found, all already seen"
            else -> "Nothing new"
        }
        Result.success()
    }

    private fun notify(context: Context, alert: Alert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.aviato.fantasybrief.R.drawable.ic_notification)
            // Tints the small icon and the app name in the shade. Teal
            // for routine, gold for a gold-star alert.
            .setColor(
                if (alert.key.endsWith(":gold")) 0xFFE8B44A.toInt()
                else 0xFF4FD0B0.toInt()
            )
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setPriority(
                if (alert.key.endsWith(":gold")) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (alert.key.endsWith(":gold")) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_STATUS
            )
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(alert.key.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "League changes", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                description = "Team changes, ownership spikes, and injuries that matter"
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "league_changes"
        private const val WORK_NAME = "fantasy_brief_poll"
        const val KEY_LEAGUE = "leagueId"
        const val KEY_SEASON = "season"

        fun schedule(context: Context, leagueId: Long, season: Int, hours: Int) {
            val request = PeriodicWorkRequestBuilder<BriefWorker>(
                hours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(KEY_LEAGUE, leagueId)
                        .putInt(KEY_SEASON, season)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** One immediate run, for testing without waiting hours. */
        fun runNow(context: Context, leagueId: Long, season: Int) {
            val request = androidx.work.OneTimeWorkRequestBuilder<BriefWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(KEY_LEAGUE, leagueId)
                        .putInt(KEY_SEASON, season)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
