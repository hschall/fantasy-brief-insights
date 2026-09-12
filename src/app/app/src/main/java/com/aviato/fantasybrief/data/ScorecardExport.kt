package com.aviato.fantasybrief.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The scorecard as data, not as a summary.
 *
 * Aggregates hide what matters. A signal that hits 60% overall might hit 90%
 * when the claimed edge is over three points and 40% below it — which would
 * mean the signal is fine and the threshold is wrong. That distinction is
 * invisible in a hit rate and obvious in a row per prediction.
 *
 * Pending predictions are included deliberately. Knowing the app made 40 calls
 * and settled 12 is itself a finding: if most never settle, the capture is
 * recording things that cannot be scored.
 */
object ScorecardExport {

    fun build(brief: Brief, predictions: List<Prediction>): String {
        val sb = StringBuilder()
        val league = brief.league
        val week = league.settings.currentMatchupPeriod
        val settledIds = brief.verdicts.map { it.prediction.id }.toSet()

        sb.append("SCORECARD EXPORT — ").append(league.settings.name)
            .append(" — season ").append(league.season)
            .append(", through week ").append(week - 1)
            .append(" (week ").append(week).append(" in progress)\n")
        sb.append("Generated ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .append("\n")
        sb.append("My team: ").append(league.myTeam?.name ?: "?").append("\n\n")

        sb.append("WHAT THIS IS\n")
        sb.append("Every recommendation the app made, recorded when made and\n")
        sb.append("scored once its week completed. `followed` is derived from my\n")
        sb.append("roster and transaction log, never self-reported. A prediction\n")
        sb.append("with a named comparison is scored against that player; one\n")
        sb.append("without is scored against this league's replacement level.\n\n")

        sb.append("KINDS\n")
        sb.append("WIRE_TIER   available player claimed to beat one of my starters\n")
        sb.append("PROJ_STALE  low projection on a depth-chart leader, claimed wrong\n")
        sb.append("BENEFICIARY player claimed to inherit work from someone out\n")
        sb.append("DROP_ORDER  one bench player claimed a better keep than another\n")
        sb.append("START_SIT   bench player claimed to belong in the lineup\n\n")

        // ---- one row per settled prediction ---------------------------
        sb.append("== SETTLED (").append(brief.verdicts.size).append(") ==\n")
        sb.append("kind | week | subject | subj_proj | subj_actual | ")
            .append("compared | comp_proj | comp_actual | claimed_edge | ")
            .append("real_edge | correct | followed\n")
        brief.verdicts
            .sortedWith(compareBy({ it.prediction.kind }, { it.prediction.week }))
            .forEach { v ->
                val p = v.prediction
                sb.append(p.kind).append(" | ").append(p.week)
                    .append(" | ").append(p.subjectName)
                    .append(" | ").append(fmt(p.subjectProjection))
                    .append(" | ").append(fmt(v.subjectActual))
                    .append(" | ").append(p.comparedToName ?: "-")
                    .append(" | ").append(p.comparedProjection?.let { fmt(it) } ?: "-")
                    .append(" | ").append(v.comparedActual?.let { fmt(it) } ?: "-")
                    .append(" | ").append(fmt(p.claimedEdge))
                    .append(" | ").append(fmt(v.realEdge))
                    .append(" | ").append(if (v.correct) "Y" else "N")
                    .append(" | ").append(if (v.followed) "Y" else "N")
                    .append("\n")
            }

        // ---- pending, so a capture problem is visible -----------------
        val pending = predictions.filter { it.id !in settledIds }
        sb.append("\n== PENDING (").append(pending.size).append(") ==\n")
        if (pending.isEmpty()) {
            sb.append("(none)\n")
        } else {
            sb.append("kind | week | subject | compared | claimed_edge | why_pending\n")
            pending.sortedBy { it.week }.take(60).forEach { p ->
                val reason = when {
                    p.week >= week -> "week not complete"
                    else -> "no actual points found (player likely dropped)"
                }
                sb.append(p.kind).append(" | ").append(p.week)
                    .append(" | ").append(p.subjectName)
                    .append(" | ").append(p.comparedToName ?: "-")
                    .append(" | ").append(fmt(p.claimedEdge))
                    .append(" | ").append(reason).append("\n")
            }
        }

        // ---- the aggregate, last, so it does not anchor the reading ---
        sb.append("\n== SUMMARY BY KIND ==\n")
        sb.append("kind | settled | correct | hit_rate | followed | ")
            .append("mean_claimed | mean_real | calibration | left_on_table | ")
            .append("saved_by_ignoring\n")
        brief.scorecard.forEach { s ->
            sb.append(s.kind).append(" | ").append(s.settled)
                .append(" | ").append(s.correct)
                .append(" | ").append(fmt(s.hitRate * 100)).append("%")
                .append(" | ").append(s.followed)
                .append(" | ").append(fmt(s.meanEdgeClaimed))
                .append(" | ").append(fmt(s.meanEdgeReal))
                .append(" | ").append(fmt(s.calibration))
                .append(" | ").append(fmt(s.leftOnTable))
                .append(" | ").append(fmt(s.savedByIgnoring))
                .append("\n")
        }

        sb.append("\n== QUESTIONS WORTH ASKING OF THIS DATA ==\n")
        sb.append("1. Does hit rate improve above a claimed_edge threshold? If a\n")
        sb.append("   signal only works above +3, the threshold is wrong, not the\n")
        sb.append("   signal.\n")
        sb.append("2. Is calibration consistently one-directional? Systematic\n")
        sb.append("   over-claiming means the edge calculation is inflated.\n")
        sb.append("3. Do the followed and declined rows have different hit rates?\n")
        sb.append("   If declined calls do better, I am filtering well and the app\n")
        sb.append("   should defer to me more.\n")
        sb.append("4. Is any kind below 50%? That is worse than a coin flip and\n")
        sb.append("   the signal should be removed rather than tuned.\n")
        sb.append("5. How many predictions never settle? A high pending count\n")
        sb.append("   means the capture records claims that cannot be scored.\n")

        val chars = sb.length
        sb.append("\n-- ").append(chars).append(" chars, ~").append(chars / 4)
            .append(" tokens --\n")
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
