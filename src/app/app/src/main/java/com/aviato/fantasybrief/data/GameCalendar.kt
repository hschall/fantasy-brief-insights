package com.aviato.fantasybrief.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One player's game this week. */
data class CalendarEntry(
    val player: RosterPlayer,
    /** "@CLE", "vs MIA", or null when we genuinely don't know. */
    val opponent: String?,
    val kickedOff: Boolean
)

/** Everyone kicking off at the same instant. */
data class CalendarSlot(
    /** Null means ESPN has not published a kickoff yet. */
    val millis: Long?,
    val timeLabel: String,
    val entries: List<CalendarEntry>
) {
    val starters: Int get() = entries.count { it.player.isStarter }
    val kickedOff: Boolean get() = entries.firstOrNull()?.kickedOff ?: false
}

data class CalendarDay(
    /** Epoch day in Mexico City. Long.MAX_VALUE sorts the TBD bucket last. */
    val sortKey: Long,
    val label: String,
    val slots: List<CalendarSlot>
)

data class WeekCalendar(
    val days: List<CalendarDay>,
    val byes: List<RosterPlayer>
) {
    private val entries: List<CalendarEntry>
        get() = days.flatMap { it.slots }.flatMap { it.entries }

    val startersLeft: Int get() = entries.count { it.player.isStarter && !it.kickedOff }
    val startersTotal: Int get() = entries.count { it.player.isStarter }

    /** Projected points from starters whose game has not begun. */
    val pointsLive: Double
        get() = entries
            .filter { it.player.isStarter && !it.kickedOff }
            .sumOf { it.player.projection ?: 0.0 }
}

/**
 * Groups a roster into the days and kickoff times of one week.
 *
 * PINNED TO MEXICO CITY, NOT THE DEVICE.
 *
 * The rest of the app formats kickoffs in the device zone, on the argument
 * that "have I still got time to change this" is a question about where you
 * are standing. A calendar is a different question — it is a plan, and a plan
 * has to stay put while you travel. So this screen is labelled with its zone
 * and never follows the handset.
 *
 * WHY THE OFFSET FROM ET IS NOT A CONSTANT.
 *
 * Mexico abolished daylight saving in 2022, so Mexico City sits on UTC-6 all
 * year. The United States did not. Through roughly week 8 an Eastern kickoff
 * is two hours behind Mexico City time; from the Sunday the US falls back in
 * early November it is one. Subtracting a fixed offset from an Eastern clock
 * time is therefore correct for half a season and silently an hour wrong for
 * the other half — and it would break during the playoff push, which is the
 * worst possible week to find out. Everything below converts from the epoch
 * millis ESPN publishes through a real ZoneId, which handles the flip without
 * knowing it happened.
 *
 * ZERO IS NOT ABSENCE. ProGame.dateMillis is 0 when ESPN has not published a
 * kickoff, which is common for late-season games that are still flexible.
 * Zero formats perfectly happily as December 1969 and would sort to the top
 * of the screen. Unknown kickoffs get their own bucket at the end instead.
 */
object GameCalendar {

    val ZONE: ZoneId = ZoneId.of("America/Mexico_City")

    /** Shown on screen so the reading is never ambiguous. */
    const val ZONE_LABEL = "MEXICO CITY"

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun dayLabel(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZONE).format(DAY).uppercase(Locale.US)

    fun timeLabel(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZONE).format(TIME)
            .replace("AM", "am").replace("PM", "pm")

    /**
     * @param now a single clock read, passed in rather than taken here, so the
     *   whole screen agrees on what "already kicked off" means and a test can
     *   pin it.
     */
    fun build(
        roster: List<RosterPlayer>,
        pro: ProTeamIndex,
        week: Int,
        now: Long = System.currentTimeMillis()
    ): WeekCalendar {

        val byes = roster.filter { pro.isOnBye(it.proTeamId, week) }
        val playing = roster.filterNot { pro.isOnBye(it.proTeamId, week) }

        val entries = playing.map { p ->
            val kickoff = pro.kickoffMillis(p.proTeamId, week)
            CalendarEntry(
                player = p,
                opponent = pro.opponent(p.proTeamId, week),
                // Presence, then comparison. A null kickoff is unknown, which
                // is not the same as "has not started".
                kickedOff = kickoff != null && now >= kickoff
            ) to kickoff
        }

        val days = entries
            .groupBy { (_, kickoff) ->
                kickoff?.let { Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate().toEpochDay() }
            }
            .map { (epochDay, sameDay) ->
                val slots = sameDay
                    .groupBy { (_, kickoff) -> kickoff }
                    .map { (kickoff, sameSlot) ->
                        CalendarSlot(
                            millis = kickoff,
                            timeLabel = kickoff?.let { timeLabel(it) } ?: "TIME TBD",
                            entries = sameSlot.map { it.first }.sortedWith(ordering)
                        )
                    }
                    // Nulls cannot appear inside a dated day, but sorting
                    // defensively costs nothing and a crash costs a Sunday.
                    .sortedBy { it.millis ?: Long.MAX_VALUE }

                CalendarDay(
                    sortKey = epochDay ?: Long.MAX_VALUE,
                    label = if (epochDay == null) "KICKOFF NOT SET"
                            else dayLabel(sameDay.first().second!!),
                    slots = slots
                )
            }
            .sortedBy { it.sortKey }

        return WeekCalendar(days, byes.sortedWith(playerOrder))
    }

    /** Starters first in lineup order, then bench by projection. */
    private val playerOrder: Comparator<RosterPlayer> =
        compareBy<RosterPlayer> { if (it.isStarter) 0 else 1 }
            .thenBy { Enums.slotSortKey(it.lineupSlotId) }
            .thenByDescending { it.projection ?: 0.0 }

    private val ordering: Comparator<CalendarEntry> =
        Comparator { a, b -> playerOrder.compare(a.player, b.player) }
}
