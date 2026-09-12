package com.aviato.fantasybrief.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fires a scheduled add shortly after a player is expected to clear waivers.
 *
 * WorkManager guarantees "eventually", not "at 11:00:03" — which is fine here,
 * because the goal is to avoid spending waiver priority on someone you only
 * want if he is free, not to beat another manager by seconds. Anyone who
 * really wants him will submit a claim and win regardless.
 *
 * Retries for a few minutes because the clear time is DERIVED, not reported.
 */
class ScheduledAddWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val store = ScheduledAddStore(ctx)
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val item = store.all().firstOrNull { it.id == id }
            ?: return@withContext Result.success()
        if (item.status != "PENDING") return@withContext Result.success()

        val secrets = SecretStore(ctx)
        if (!secrets.hasCredentials) {
            store.update(id, "ABORTED", "Not signed in when it fired")
            return@withContext Result.success()
        }

        val client = EspnClient(secrets)
        val repo = LeagueRepository(client, secrets)

        repeat(ATTEMPTS) { attempt ->
            val out = repo.load(item.season, item.leagueId)
            if (out is LeagueRepository.Outcome.Ok) {
                val league = out.league
                val team = league.myTeam

                // The roster can change between scheduling and firing. Dropping
                // someone else instead would be a silent, expensive mistake.
                val dropStillThere =
                    team?.roster?.any { it.playerId == item.dropPlayerId } == true
                if (!dropStillThere) {
                    store.update(id, "ABORTED",
                        "${item.dropPlayerName} was no longer on your roster")
                    return@withContext Result.success()
                }

                // Someone claimed him on waivers. They paid priority for it,
                // which is exactly the trade this feature declines to make.
                val rosteredElsewhere = league.teams.any { t ->
                    t.roster.any { it.playerId == item.addPlayerId }
                }
                if (rosteredElsewhere) {
                    store.update(id, "LOST", "Claimed by another team on waivers")
                    return@withContext Result.success()
                }

                val res = EspnWriteClient(secrets).addPlayer(
                    item.season, item.leagueId, team!!.id,
                    league.settings.scoringPeriodId,
                    addPlayerId = item.addPlayerId,
                    dropPlayerId = item.dropPlayerId,
                    isWaiverClaim = false, dryRun = false
                )
                if (res.ok) {
                    store.update(id, "DONE", "Added on attempt ${attempt + 1}")
                    return@withContext Result.success()
                }
                // Still on waivers means the derived clear time was early —
                // wait and try again rather than giving up.
                val msg = EspnWriteClient(secrets).errorMessage(res)
                if (attempt == ATTEMPTS - 1) {
                    store.update(id, "EXPIRED", msg.take(120))
                    return@withContext Result.success()
                }
            }
            delay(RETRY_MILLIS)
        }
        Result.success()
    }

    companion object {
        private const val ATTEMPTS = 30
        private const val RETRY_MILLIS = 45_000L
        const val KEY_ID = "scheduledId"

        /**
         * Still enqueued as a one-shot, but it is now a BACKUP: the
         * periodic sweep is what actually makes this reliable, because a
         * job delayed by tens of hours gets dropped.
         */
        fun schedule(context: Context, item: ScheduledAdd) {
            val delayMs = (item.firesAtMillis - System.currentTimeMillis())
                .coerceAtLeast(0L)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sched_${item.id}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduledAddWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putString(KEY_ID, item.id).build())
                    .build()
            )
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("sched_$id")
        }
    }
}
