package com.aviato.fantasybrief.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WRITE client. Separate class, separate host, deliberately awkward to reach.
 *
 * Everything else in this project is read-only, and that has been load-bearing:
 * every wrong call the app has made so far cost a confusing line in a dump.
 * A wrong call here costs points. So:
 *
 *   - Different host (lm-api-writes) — a read call can never accidentally
 *     become a write.
 *   - Every method takes an explicit confirmation flag.
 *   - dryRun defaults to TRUE. Callers must opt in to actually sending.
 *
 * The payload shape below is INFERRED from ESPN's web client and has NOT been
 * verified against a live league. Probe before trusting it.
 */
class EspnWriteClient(private val secrets: SecretStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class WriteResult(
        val code: Int,
        val body: String,
        val sentPayload: String,
        val error: String? = null,
        val wasDryRun: Boolean = false
    ) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * Moves players between lineup slots.
     *
     * ESPN models a lineup change as a TRANSACTION of type ROSTER containing
     * one item per player being moved, each naming the slot he leaves and the
     * slot he enters. A swap is therefore TWO items, not one.
     *
     * @param moves playerId to (fromSlotId, toSlotId)
     * @param dryRun when true, builds and returns the payload without sending
     */
    fun setLineup(
        season: Int,
        leagueId: Long,
        teamId: Int,
        scoringPeriod: Int,
        moves: List<Triple<Int, Int, Int>>,
        dryRun: Boolean = true
    ): WriteResult {
        val items = JSONArray()
        moves.forEach { (playerId, fromSlot, toSlot) ->
            items.put(
                JSONObject().apply {
                    put("playerId", playerId)
                    put("type", "LINEUP")
                    put("fromLineupSlotId", fromSlot)
                    put("toLineupSlotId", toSlot)
                }
            )
        }

        val payload = JSONObject().apply {
            put("isLeagueManager", false)
            put("teamId", teamId)
            put("type", "ROSTER")
            put("memberId", secrets.swid ?: "")
            put("scoringPeriodId", scoringPeriod)
            put("executionType", "EXECUTE")
            put("items", items)
        }.toString()

        if (dryRun) {
            return WriteResult(0, "(not sent)", payload, wasDryRun = true)
        }
        if (!secrets.hasCredentials) {
            return WriteResult(-1, "", payload, "No stored cookies")
        }

        val url = "$WRITE_HOST/apis/v3/games/ffl/seasons/$season/segments/0/" +
            "leagues/$leagueId/transactions/"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", secrets.cookieHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                WriteResult(r.code, r.body?.string().orEmpty(), payload)
            }
        } catch (e: Exception) {
            WriteResult(-1, "", payload, e.message ?: e.toString())
        }
    }

    /**
     * Add a player, dropping one to make room.
     *
     * A free agent is an immediate ADD. A player on waivers is a CLAIM, which
     * queues until the next waiver run and SPENDS PRIORITY — in a league where
     * priority does not reset, that is a permanent cost for a temporary gain.
     *
     * VERIFIED 2026-09-07 against a live league:
     *   free agent  envelope FREEAGENT, item ADD, executionType EXECUTE
     *   waiver claim envelope WAIVER,   item ADD, executionType EXECUTE
     * Only the ENVELOPE differs. The item type stays ADD, and EXECUTE is
     * correct even though a claim queues — QUEUE is rejected as invalid.
     * Every item is directional: ADD needs toTeamId, DROP needs fromTeamId,
     * and team 0 is the free agent pool.
     *
     * @param dropPlayerId required when the roster is full
     * @param dryRun defaults TRUE. Callers must opt in to actually sending.
     */
    fun addPlayer(
        season: Int,
        leagueId: Long,
        teamId: Int,
        scoringPeriod: Int,
        addPlayerId: Int,
        dropPlayerId: Int?,
        isWaiverClaim: Boolean,
        dryRun: Boolean = true,
        // Overrides for probing. A waiver claim QUEUES rather than
        // executing, so EXECUTE is likely wrong for it.
        envelopeType: String? = null,
        executionType: String? = null,
        itemType: String? = null
    ): WriteResult {
        val items = JSONArray()
        items.put(
            JSONObject().apply {
                put("playerId", addPlayerId)
                // ADD for both. A claim is not a different kind of item.
                put("type", itemType ?: "ADD")
                put("toLineupSlotId", 20)   // always to the bench
                // Items are DIRECTIONAL. An ADD needs a destination and a
                // DROP needs an origin; ESPN rejects either without both.
                // Team 0 is the free agent pool.
                put("fromTeamId", 0)
                put("toTeamId", teamId)
            }
        )
        dropPlayerId?.let {
            items.put(
                JSONObject().apply {
                    put("playerId", it)
                    put("type", "DROP")
                    put("fromLineupSlotId", 20)
                    put("toLineupSlotId", -1)
                    put("fromTeamId", teamId)
                    put("toTeamId", 0)
                }
            )
        }

        val payload = JSONObject().apply {
            put("isLeagueManager", false)
            put("teamId", teamId)
            put("type", envelopeType ?: if (isWaiverClaim) "WAIVER" else "FREEAGENT")
            put("memberId", secrets.swid ?: "")
            put("scoringPeriodId", scoringPeriod)
            // A waiver claim is QUEUED, not executed now. Sending EXECUTE for
            // one would either fail or, worse, quietly do the wrong thing.
            put("executionType", executionType ?: "EXECUTE")
            put("items", items)
        }.toString()

        if (dryRun) return WriteResult(0, "(not sent)", payload, wasDryRun = true)
        if (!secrets.hasCredentials) {
            return WriteResult(-1, "", payload, "No stored cookies")
        }

        val url = "$WRITE_HOST/apis/v3/games/ffl/seasons/$season/segments/0/" +
            "leagues/$leagueId/transactions/"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", secrets.cookieHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                WriteResult(r.code, r.body?.string().orEmpty(), payload)
            }
        } catch (e: Exception) {
            WriteResult(-1, "", payload, e.message ?: e.toString())
        }
    }

    /**
     * ESPN returns structured errors. Verified shape from a 409:
     *   {"messages":[...],"details":[{"message":..,"type":"TRAN_ROSTER_SAME_SLOT"}]}
     * Show ESPN's own wording rather than a status code — it names the player
     * and the reason.
     */
    fun errorMessage(result: WriteResult): String {
        if (result.ok) return ""
        result.error?.let { return it }
        return runCatching {
            val root = JSONObject(result.body)
            val messages = root.optJSONArray("messages")
            if (messages != null && messages.length() > 0) {
                val text = (0 until messages.length())
                    .joinToString(" ") { messages.optString(it) }
                // "Invalid input" names nothing. Append the detail type, which
                // is what actually identifies the problem.
                val type = root.optJSONArray("details")?.optJSONObject(0)
                    ?.optString("type", "") ?: ""
                if (text.length < 30 && type.isNotBlank()) "$text [$type]" else text
            } else "HTTP ${result.code}"
        }.getOrDefault(
            // A vague message like "Invalid input" is useless on its own —
            // fall through to the raw body so the actual complaint is visible.
            "HTTP ${result.code}: ${result.body.take(300)}"
        )
    }

    companion object {
        // NOT lm-api-reads. Keeping the hosts distinct means a read path can
        // never turn into a write by accident.
        const val WRITE_HOST = "https://lm-api-writes.fantasy.espn.com"
    }
}

/**
 * Validates a proposed lineup before anything is sent.
 *
 * ESPN will reject some illegal moves and silently accept others, so the
 * checks that protect you have to run here. A locked player is the one that
 * matters: his game has started, and moving him is either refused or
 * pointless.
 */
object LineupValidator {

    data class Problem(val severity: String, val message: String)

    fun check(
        team: FantasyTeam,
        settings: LeagueSettings,
        pro: ProTeamIndex,
        week: Int,
        proposed: Map<Int, Int>   // playerId to new slotId
    ): List<Problem> {
        val problems = mutableListOf<Problem>()
        val byId = team.roster.associateBy { it.playerId }

        proposed.forEach { (playerId, toSlot) ->
            val p = byId[playerId] ?: return@forEach

            if (pro.hasKickedOff(p.proTeamId, week)) {
                problems.add(Problem("BLOCK",
                    "${p.name}'s game has already started — he is locked."))
            }
            if (!p.eligibleSlots.contains(toSlot)) {
                problems.add(Problem("BLOCK",
                    "${p.name} is not eligible for ${Enums.slot(toSlot)}."))
            }
            if (Enums.isStarterSlot(toSlot) && pro.isOnBye(p.proTeamId, week)) {
                problems.add(Problem("WARN",
                    "${p.name} is on bye and would be starting."))
            }
            if (Enums.isStarterSlot(toSlot) && !p.healthy) {
                problems.add(Problem("WARN",
                    "${p.name} is ${p.injuryStatus} and would be starting."))
            }
        }

        // Resulting lineup must fill each slot exactly.
        val finalSlots = team.roster.associate { p ->
            p.playerId to (proposed[p.playerId] ?: p.lineupSlotId)
        }
        settings.starterSlots.forEach { rule ->
            val filled = finalSlots.count { it.value == rule.slotId }
            if (filled != rule.count) {
                problems.add(Problem("BLOCK",
                    "${Enums.slot(rule.slotId)} would have $filled players, " +
                        "needs ${rule.count}."))
            }
        }

        return problems
    }
}
