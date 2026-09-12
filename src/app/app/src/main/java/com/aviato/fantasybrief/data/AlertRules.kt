package com.aviato.fantasybrief.data

/** One thing worth waking the phone for. */
data class Alert(
    val key: String,          // dedupe identity: league + player + kind
    val title: String,
    val body: String
)

/**
 * Decides what is worth a notification, which is a much higher bar than what
 * is worth a research flag. The queue can afford forty items; a notification
 * cannot afford one that fires for nothing.
 */
object AlertRules {

    private const val OWNERSHIP_JUMP = 5.0
    private const val OWNERSHIP_JUMP_MAX_OWNED = 30.0
    private const val RIVAL_DROP_PROJECTION = 8.0

    fun evaluate(
        league: League,
        wire: List<WirePlayer>,
        pro: ProTeamIndex,
        previous: ObservationRun
    ): List<Alert> {
        val alerts = mutableListOf<Alert>()
        val leagueName = league.settings.name
        val myTeamId = league.myTeamId

        val rostered = league.teams.flatMap { t -> t.roster.map { t to it } }
        val ownerOf = rostered.associate { (t, p) -> p.playerId to t }

        // ---- TEAM CHANGE, rostered or free agent -------------------------
        val currentTeams = buildMap<Int, Pair<Int, String>> {
            wire.forEach { put(it.playerId, it.proTeamId to it.name) }
            rostered.forEach { (_, p) -> put(p.playerId, p.proTeamId to p.name) }
        }
        currentTeams.forEach { (id, pair) ->
            val (now, name) = pair
            val was = previous.players[id] ?: return@forEach
            if (was.proTeamId == now || now == 0 || was.proTeamId == 0) return@forEach

            val owner = ownerOf[id]
            val where = when {
                owner == null -> "free agent"
                owner.id == myTeamId -> "on your roster"
                else -> "on ${owner.name}"
            }
            alerts.add(
                Alert(
                    key = "${league.id}:$id:team",
                    title = "$leagueName · $name moved to ${pro.abbrev(now)}",
                    body = "From ${pro.abbrev(was.proTeamId)}. Currently $where. " +
                        "ESPN's projection will not have caught up yet."
                )
            )
        }

        // ---- OWNERSHIP JUMP on a low-owned player ------------------------
        wire.forEach { p ->
            val was = previous.players[p.playerId] ?: return@forEach
            val rise = p.percentOwned - was.percentOwned
            if (rise >= OWNERSHIP_JUMP && p.percentOwned < OWNERSHIP_JUMP_MAX_OWNED) {
                alerts.add(
                    Alert(
                        key = "${league.id}:${p.playerId}:own",
                        title = "$leagueName · ${p.name} climbing fast",
                        body = "${fmt(was.percentOwned)}% to ${fmt(p.percentOwned)}% " +
                            "in ${previous.ageHours}h. ${p.position}, " +
                            "${pro.abbrev(p.proTeamId)}. Still available."
                    )
                )
            }
        }

        // ---- NEW injury on MY roster only --------------------------------
        // "New" matters: an existing QUESTIONABLE tag is not news, a change
        // in status is.
        league.myTeam?.roster?.forEach { p ->
            if (p.healthy) return@forEach
            val was = previous.players[p.playerId] ?: return@forEach
            val wasStatus = was.injuryStatus?.uppercase()
            val nowStatus = p.injuryStatus?.uppercase() ?: return@forEach
            if (wasStatus == nowStatus) return@forEach

            alerts.add(
                Alert(
                    key = "${league.id}:${p.playerId}:inj:$nowStatus",
                    title = "$leagueName · ${p.name} is now $nowStatus",
                    body = "${p.position}, ${pro.abbrev(p.proTeamId)}, on your roster. " +
                        (if (wasStatus.isNullOrBlank()) "" else "Was $wasStatus. ") +
                        "Check practice reports."
                )
            )
        }

        // Rival drops are handled by AlertRules.fromTransactions below,
        // sourced from ESPN's activity log rather than inferred from
        // roster membership.
        // ---- A RIVAL dropped someone worth having (REMOVED) --------------
        /*
        league.teams.filter { it.id != myTeamId }.forEach { team ->
            val before = previous.rosters[team.id] ?: return@forEach
            val now = team.roster.map { it.playerId }.toSet()
            (before - now).forEach { droppedId ->
                val onWire = wire.firstOrNull { it.playerId == droppedId } ?: return@forEach
                if ((onWire.projection ?: 0.0) < RIVAL_DROP_PROJECTION) return@forEach
                alerts.add(
                    Alert(
                        key = "${league.id}:$droppedId:drop",
                        title = "$leagueName · ${team.name} dropped ${onWire.name}",
                        body = "${onWire.position}, ${pro.abbrev(onWire.proTeamId)}, " +
                            "projected ${fmt(onWire.projection ?: 0.0)}. On the wire now."
                    )
                )
            }
        }

        */

        return alerts.distinctBy { it.key }
    }

    /**
     * Rival transactions worth waking the phone for. Sourced from the
     * activity log, so these fire exactly once per real event regardless
     * of how often the worker runs.
     */
    /**
     * The one alert worth interrupting a day for.
     *
     * An ELITE starter who is doubtful or worse, whose direct backup is
     * unowned. That is a starting NFL job opening with the man who inherits it
     * available for a claim — the only pattern in this app where being an hour
     * late costs the player rather than a few points.
     *
     * Deliberately narrow: questionable does not qualify, and neither does a
     * non-elite starter. An alarm that fires weekly is one you stop reading.
     */
    fun goldStar(
        league: League,
        pairs: List<AtRiskPair>,
        replacement: ReplacementLevel
    ): List<Alert> = pairs
        .filter { it.certainty >= 70 }
        .filter {
            replacement.rank(it.starter.position, it.starter.projection) == "ELITE"
        }
        .map { p ->
            Alert(
                key = "${league.id}:${p.backup.playerId}:gold",
                title = "${p.starter.name} is ${p.certaintyLabel.lowercase()}",
                body = "${p.backup.name} is ${p.starter.position}" +
                    "${p.backupDepthRank ?: 2} behind him and " +
                    (if (p.onWaivers) "on waivers" else "a free agent") +
                    " at ${p.backup.percentOwned.toInt()}% owned. " +
                    (if (p.isMine) "He starts for you."
                     else "He starts for ${p.ownerName}.")
            )
        }

    fun fromTransactions(
        league: League,
        transactions: List<Transaction>,
        wire: List<WirePlayer>,
        names: Map<Int, String>
    ): List<Alert> {
        val leagueName = league.settings.name
        val teamName = league.teams.associate { it.id to it.name }

        return transactions
            .filter { it.teamId != league.myTeamId }
            .filter { it.kind == TxKind.DROP || it.kind == TxKind.TRADE }
            .mapNotNull { tx ->
                val who = names[tx.playerId] ?: return@mapNotNull null
                val onWire = wire.firstOrNull { it.playerId == tx.playerId }

                // A drop only matters if the player is worth claiming.
                if (tx.kind == TxKind.DROP &&
                    (onWire?.projection ?: 0.0) < RIVAL_DROP_PROJECTION
                ) return@mapNotNull null

                val team = teamName[tx.teamId] ?: "A rival"
                Alert(
                    key = "${league.id}:${tx.id}",
                    title = if (tx.kind == TxKind.DROP)
                        "$leagueName · $team dropped $who"
                    else "$leagueName · trade involving $who",
                    body = if (tx.kind == TxKind.DROP)
                        "Projected ${fmt(onWire?.projection ?: 0.0)}. Available now."
                    else "$team acquired $who. Check what moved the other way."
                )
            }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
