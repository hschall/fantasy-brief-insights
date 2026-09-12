package com.aviato.fantasybrief.data

/**
 * Turns what the app is currently claiming into recorded predictions.
 *
 * Capture is automatic and happens on every refresh. The store deduplicates by
 * league+week+kind+players, so a recommendation seen on ten refreshes is one
 * prediction — otherwise the scorecard would measure how often the app was
 * opened rather than how often it was right.
 *
 * Only claims with a NAMED COMPARISON are recorded. "Player X is good" cannot
 * be scored; "X beats Y by 2.8" can.
 */
object PredictionCapture {

    fun from(
        league: League,
        week: Int,
        tiers: Map<Tier, List<TieredPlayer>>,
        beneficiaries: List<Beneficiary>,
        dropCandidates: List<DropCandidate>,
        depth: DepthCharts,
        replacement: ReplacementLevel
    ): List<Prediction> {
        val team = league.myTeam ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf<Prediction>()

        fun add(
            kind: String, subject: Int, subjectName: String, subjectProj: Double,
            comparedId: Int?, comparedName: String?, comparedProj: Double?,
            edge: Double, note: String
        ) {
            out.add(
                Prediction(
                    id = "$kind:$subject:${comparedId ?: 0}:$week:$now",
                    leagueId = league.id, season = league.season, week = week,
                    madeAtMillis = now, kind = kind,
                    subjectId = subject, subjectName = subjectName,
                    comparedToId = comparedId, comparedToName = comparedName,
                    claimedEdge = edge, subjectProjection = subjectProj,
                    comparedProjection = comparedProj, note = note
                )
            )
        }

        // 1. Wire tiers: "this available player beats a starter of mine".
        (tiers[Tier.ELITE].orEmpty() + tiers[Tier.SOLID].orEmpty()).forEach { t ->
            val p = t.player
            val worst = team.roster
                .filter { it.isStarter && it.position == p.position }
                .minByOrNull { it.projection ?: 0.0 } ?: return@forEach
            val edge = (p.projection ?: 0.0) - (worst.projection ?: 0.0)
            if (edge <= 0.0) return@forEach
            add(
                "WIRE_TIER", p.playerId, p.name, p.projection ?: 0.0,
                worst.playerId, worst.name, worst.projection,
                edge, "${t.tier.label} over ${worst.name}"
            )
        }

        // 2. PROJ_STALE: the thesis this whole project is built on — a low
        //    projection on a player who tops his depth chart is wrong.
        //    Scored against the projection itself, since there is no rival.
        (tiers.values.flatten()).forEach { t ->
            val p = t.player
            val rank = depth.rank(p.proTeamId, p.playerId) ?: return@forEach
            if (rank > 2 || (p.projection ?: 0.0) >= 3.0) return@forEach
            add(
                "PROJ_STALE", p.playerId, p.name, p.projection ?: 0.0,
                null, null, null,
                // The claim is that he beats replacement level despite the
                // projection, so that is the bar.
                (replacement.solid(p.position) ?: 0.0) - (p.projection ?: 0.0),
                "depth rank $rank, projection ${p.projection}"
            )
        }

        // 3. Beneficiaries: "this player inherits work from someone out".
        beneficiaries.forEach { b ->
            add(
                "BENEFICIARY", b.playerId, b.name, b.projection ?: 0.0,
                null, null, null,
                (replacement.solid(b.position) ?: 0.0) - (b.projection ?: 0.0),
                "depth rank ${b.depthRank} behind ${b.blockedBy}"
            )
        }

        // 4. Drop candidates: "this bench player is the least valuable".
        //    Scored against the player immediately above him in the list, so
        //    a wrong ordering is visible.
        dropCandidates.zipWithNext().forEach { (lower, higher) ->
            add(
                "DROP_ORDER", higher.player.playerId, higher.player.name,
                higher.player.projection ?: 0.0,
                lower.player.playerId, lower.player.name, lower.player.projection,
                (higher.player.projection ?: 0.0) - (lower.player.projection ?: 0.0),
                "ranked above ${lower.player.name} as a keep"
            )
        }

        // 5. Start/sit: every bench player the app says should be starting.
        team.roster.filterNot { it.isStarter }.forEach { p ->
            val displaceable = team.roster.filter { st ->
                st.isStarter &&
                    p.eligibleSlots.contains(st.lineupSlotId) &&
                    st.eligibleSlots.contains(p.lineupSlotId)
            }
            val worst = displaceable.minByOrNull { it.projection ?: 0.0 } ?: return@forEach
            val edge = (p.projection ?: 0.0) - (worst.projection ?: 0.0)
            if (edge <= 1.0) return@forEach
            add(
                "START_SIT", p.playerId, p.name, p.projection ?: 0.0,
                worst.playerId, worst.name, worst.projection,
                edge, "start over ${worst.name}"
            )
        }

        return out
    }
}
