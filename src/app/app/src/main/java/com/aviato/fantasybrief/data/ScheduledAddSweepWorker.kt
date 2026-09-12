package com.aviato.fantasybrief.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Picks up scheduled adds whose time has passed.
 *
 * MEASURED 2026-09-10, and this exists because of it: a scheduled add 44
 * hours out never fired. WorkManager makes no promise about a one-shot
 * delayed that far, Samsung's battery manager drops it, and both background
 * workers in fact went silent from 01:19 until the app was next opened.
 *
 * The watcher's periodic pattern, by contrast, ran 144 times without missing.
 * So the one-shot stays as a fast path and this sweep is what makes the
 * feature reliable — it also rescues an add whose fire time passed while the
 * phone was off, which the one-shot loses entirely.
 */
class ScheduledAddSweepWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = ScheduledAddStore(applicationContext)
        val now = System.currentTimeMillis()

        val due = store.all().filter {
            it.status == "PENDING" && it.firesAtMillis <= now
        }
        if (due.isEmpty()) return Result.success()

        // Hand each to the real worker rather than duplicating its logic:
        // the abort conditions and the retry loop live there.
        due.forEach { item ->
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "sched_sweep_${item.id}",
                androidx.work.ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScheduledAddWorker>()
                    .setInputData(
                        Data.Builder()
                            .putString(ScheduledAddWorker.KEY_ID, item.id)
                            .build()
                    )
                    .build()
            )
        }
        return Result.success()
    }

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sched_sweep",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScheduledAddSweepWorker>(
                    15, TimeUnit.MINUTES
                ).build()
            )
        }
    }
}
