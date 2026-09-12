package com.aviato.fantasybrief.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * An add queued to fire once a player clears waivers.
 *
 * The point is to avoid spending waiver priority on someone you only want if
 * he is free. If someone else claims him on waivers, this loses — and that is
 * the correct outcome, because they wanted him enough to pay for it.
 */
data class ScheduledAdd(
    val id: String,
    val leagueId: Long,
    val season: Int,
    val addPlayerId: Int,
    val addPlayerName: String,
    val dropPlayerId: Int,
    val dropPlayerName: String,
    val firesAtMillis: Long,
    val createdAtMillis: Long,
    /** PENDING, DONE, LOST, ABORTED, EXPIRED */
    val status: String = "PENDING",
    val note: String = ""
)

class ScheduledAddStore(context: Context) {

    private fun file(context: Context) = File(context.filesDir, "scheduled_adds.json")
    private val ctx = context.applicationContext

    fun all(): List<ScheduledAdd> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        ScheduledAdd(
                            id = o.optString("id"),
                            leagueId = o.optLong("l"),
                            season = o.optInt("s"),
                            addPlayerId = o.optInt("a"),
                            addPlayerName = o.optString("an"),
                            dropPlayerId = o.optInt("d"),
                            dropPlayerName = o.optString("dn"),
                            firesAtMillis = o.optLong("f"),
                            createdAtMillis = o.optLong("c"),
                            status = o.optString("st", "PENDING"),
                            note = o.optString("n", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun pending(leagueId: Long) =
        all().filter { it.leagueId == leagueId && it.status == "PENDING" }

    fun save(list: List<ScheduledAdd>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id); put("l", s.leagueId); put("s", s.season)
                    put("a", s.addPlayerId); put("an", s.addPlayerName)
                    put("d", s.dropPlayerId); put("dn", s.dropPlayerName)
                    put("f", s.firesAtMillis); put("c", s.createdAtMillis)
                    put("st", s.status); put("n", s.note)
                }
            )
        }
        file(ctx).writeText(arr.toString())
    }

    fun add(item: ScheduledAdd) = save(all().filterNot { it.id == item.id } + item)

    fun update(id: String, status: String, note: String) =
        save(all().map { if (it.id == id) it.copy(status = status, note = note) else it })

    fun remove(id: String) = save(all().filterNot { it.id == id })
}

/**
 * When does a player dropped at time T clear waivers?
 *
 * NOT "drop time plus waiverHours". ESPN runs waivers on fixed days at a fixed
 * hour, so a player dropped on Tuesday in a Monday-11:00 league clears the
 * following Monday. The waiver period is a MINIMUM age, not a countdown.
 *
 * DERIVED, not reported — ESPN gives the schedule, not the clear time. Verify
 * against a real clear before trusting it for anything expensive.
 */
object WaiverClock {

    /**
     * When waivers actually run, from the ACTIVITY LOG rather than
     * waiverProcessStatus.
     *
     * A type-181 drop is the drop half of a processed waiver claim, so its
     * timestamp IS a run. There are far more of these than there are entries
     * in waiverProcessStatus — twelve across two leagues versus one — and
     * they are the same event. Verified 2026-09-08: every 181 in both leagues
     * landed between 01:03 and 01:13 local, consistently, daily.
     */
    fun runsFromActivity(transactions: List<Transaction>): List<Long> =
        transactions
            .filter { it.isWaiverRun }
            .map { it.whenMillis }
            .sorted()


    private val DAYS = mapOf(
        "SUNDAY" to Calendar.SUNDAY, "MONDAY" to Calendar.MONDAY,
        "TUESDAY" to Calendar.TUESDAY, "WEDNESDAY" to Calendar.WEDNESDAY,
        "THURSDAY" to Calendar.THURSDAY, "FRIDAY" to Calendar.FRIDAY,
        "SATURDAY" to Calendar.SATURDAY
    )

    /**
     * OBSERVED runs beat the settings.
     *
     * VERIFIED 2026-09-08 and this is the whole reason the feature was wrong:
     * both leagues report waiverProcessHour 11 and a six-day list excluding
     * Tuesday, but every recorded run happened at 03:05–03:13 ET, and Chem ran
     * on a Tuesday. The settings do not describe reality — waiverProcessHour is
     * not an hour-of-day in Eastern, whatever else it is.
     *
     * So: derive the clock from waiverProcessStatus, which is a log of runs
     * ESPN actually performed. Fall back to the settings only when there is no
     * history to learn from.
     *
     * @param observedRuns epoch millis of past runs, newest last
     */
    fun clearsAtObserved(
        droppedAtMillis: Long,
        observedRuns: List<Long>,
        waiverHours: Int
    ): Long? {
        // One sample is not a median. IPADE had a single entry in
        // waiverProcessStatus and predicted 09:00 when every observed run in
        // both leagues lands at 01:05-01:13 local.
        if (observedRuns.size < 3) return null
        val earliest = droppedAtMillis + waiverHours * 3_600_000L

        // Typical hour and minute of a run, in the device's zone.
        val cal = Calendar.getInstance(Locale.US)
        val hours = observedRuns.map {
            cal.timeInMillis = it
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }
        val typical = hours.sorted()[hours.size / 2]

        // Runs look daily in both leagues, so walk day by day from the first
        // eligible moment and take the next slot at that time.
        val slot = Calendar.getInstance(Locale.US).apply {
            timeInMillis = earliest
            set(Calendar.HOUR_OF_DAY, typical / 60)
            set(Calendar.MINUTE, typical % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(9) {
            if (slot.timeInMillis >= earliest) return slot.timeInMillis
            slot.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /**
     * @param processDays league setting, e.g. ["MONDAY"]
     * @param processHour hour of day, league-local (ESPN reports Eastern)
     * @return the first processing moment at least waiverHours after the drop
     */
    fun clearsAt(
        droppedAtMillis: Long,
        processDays: List<String>,
        processHour: Int,
        waiverHours: Int
    ): Long? {
        if (processDays.isEmpty()) return null
        val days = processDays.mapNotNull { DAYS[it.uppercase()] }.toSet()
        if (days.isEmpty()) return null

        // ESPN reports the processing hour in US Eastern.
        val eastern = TimeZone.getTimeZone("America/New_York")
        val earliest = droppedAtMillis + waiverHours * 3_600_000L

        // Start from the DAY containing the earliest eligible moment, with
        // the clock zeroed, so day arithmetic never carries an hour forward.
        // VERIFIED 2026-09-07 against IPADE: dropped Mon 18:31 ET, 24h period,
        // processing days exclude Tuesday -> clears Wed 11:00 ET, which is
        // 09:00 in Mexico City. The app was right; describe() renders local
        // time and that is correct.
        val cal = Calendar.getInstance(eastern, Locale.US).apply {
            timeInMillis = earliest
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Walk forward to the first processing slot at or after `earliest`.
        // Two weeks is far more than any real schedule needs.
        repeat(15) {
            if (cal.get(Calendar.DAY_OF_WEEK) in days) {
                val slot = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, processHour)
                }
                if (slot.timeInMillis >= earliest) return slot.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            // Re-zero after the add: DAY_OF_MONTH arithmetic across a DST
            // boundary can shift the hour.
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
        }
        return null
    }

    /** Every input and the result, in both zones. Diagnostic only. */
    fun debug(
        droppedAtMillis: Long,
        processDays: List<String>,
        processHour: Int,
        waiverHours: Int
    ): String {
        val et = java.text.SimpleDateFormat("EEE d MMM HH:mm z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/New_York")
        }
        val local = java.text.SimpleDateFormat("EEE d MMM HH:mm z", Locale.US)
        val result = clearsAt(droppedAtMillis, processDays, processHour, waiverHours)
        return buildString {
            append("days=").append(processDays).append("  hour=").append(processHour)
            append("  waiverHours=").append(waiverHours).append("\n")
            append("dropped   ET ").append(et.format(java.util.Date(droppedAtMillis)))
            append("  |  local ").append(local.format(java.util.Date(droppedAtMillis)))
            append("\n")
            val earliest = droppedAtMillis + waiverHours * 3_600_000L
            append("earliest  ET ").append(et.format(java.util.Date(earliest)))
            append("  |  local ").append(local.format(java.util.Date(earliest)))
            append("\n")
            if (result == null) append("clearsAt  NULL") else {
                append("clearsAt  ET ").append(et.format(java.util.Date(result)))
                append("  |  local ").append(local.format(java.util.Date(result)))
                append("\n")
                append("describe() -> ").append(describe(result))
            }
        }
    }

    fun describe(millis: Long): String =
        java.text.SimpleDateFormat("EEE d MMM, h:mma", Locale.US)
            .format(java.util.Date(millis))
            .replace("AM", "am").replace("PM", "pm")
}
