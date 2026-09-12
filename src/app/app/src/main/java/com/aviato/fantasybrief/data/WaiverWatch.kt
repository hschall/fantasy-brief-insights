package com.aviato.fantasybrief.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records the exact moment a player stops being on waivers.
 *
 * The scheduled add tells us whether a PREDICTION was right. This tells us
 * what actually happened, which is what the prediction needs to be built on.
 * The settings have already been shown to be wrong about both the hour and the
 * days, so the only trustworthy source is observation.
 *
 * Polls every 15 minutes — WorkManager's floor for periodic work. That bounds
 * the measurement error, which is fine for learning a daily cadence.
 */
class WaiverWatchStore(context: Context) {

    private val file = File(context.filesDir, "waiver_watch.json")

    data class Watch(
        val leagueId: Long,
        val season: Int,
        val playerId: Int,
        val playerName: String,
        val droppedAtMillis: Long,
        val predictedMillis: Long,
        val firstSeenFreeMillis: Long?,
        val lastCheckedMillis: Long,
        val checks: Int
    )

    fun all(): List<Watch> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Watch(
                            o.optLong("l"), o.optInt("s"), o.optInt("p"),
                            o.optString("n"), o.optLong("d"), o.optLong("pr"),
                            if (o.isNull("f")) null else o.optLong("f"),
                            o.optLong("c"), o.optInt("k")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<Watch>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(
                JSONObject().apply {
                    put("l", w.leagueId); put("s", w.season); put("p", w.playerId)
                    put("n", w.playerName); put("d", w.droppedAtMillis)
                    put("pr", w.predictedMillis)
                    w.firstSeenFreeMillis?.let { put("f", it) }
                    put("c", w.lastCheckedMillis); put("k", w.checks)
                }
            )
        }
        file.writeText(arr.toString())
    }

    fun add(w: Watch) = save(all().filterNot {
        it.playerId == w.playerId && it.leagueId == w.leagueId
    } + w)

    fun clear() = file.delete()
}

class WaiverWatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val store = WaiverWatchStore(ctx)
        val secrets = SecretStore(ctx)
        if (!secrets.hasCredentials) return@withContext Result.success()

        val watches = store.all()
        // Nothing pending: every watch has already seen its player go free.
        if (watches.none { it.firstSeenFreeMillis == null }) {
            return@withContext Result.success()
        }

        val client = EspnClient(secrets)
        val wireRepo = WireRepository(client)
        val now = System.currentTimeMillis()

        val updated = watches.map { w ->
            if (w.firstSeenFreeMillis != null) return@map w
            val pool = wireRepo.loadPool(w.season, w.leagueId, 1)
            val found = pool.firstOrNull { it.playerId == w.playerId }
            // Gone from the pool means somebody claimed him — also an answer,
            // just not the one we were measuring.
            val free = found != null && found.status != "WAIVERS"
            w.copy(
                firstSeenFreeMillis = if (free) now else null,
                lastCheckedMillis = now,
                checks = w.checks + 1
            )
        }
        store.save(updated)
        Result.success()
    }

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "waiver_watch",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WaiverWatchWorker>(15, TimeUnit.MINUTES)
                    .build()
            )
        }
    }
}
