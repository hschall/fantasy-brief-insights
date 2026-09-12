package com.aviato.fantasybrief.data

import org.json.JSONObject

enum class TxKind { ADD, WAIVER_ADD, DROP, TRADE, LINEUP, UNKNOWN }

/** Did a logged trade offer actually go through? */
enum class TradeState { COMPLETED, NOT_COMPLETED, UNKNOWN }

data class Transaction(
    val id: String,              // stable: topicId + message id
    val kind: TxKind,
    val whenMillis: Long,
    val teamId: Int,             // team the action belongs to
    val counterpartyTeamId: Int?, // trades only
    val playerId: Int,
    val fromSlot: Int?,
    val toSlot: Int?,
    /** True when this drop was executed BY a waiver run (message 181).
     *  Its timestamp is therefore a waiver run, which is the best signal
     *  we have for when waivers actually execute. */
    val isWaiverRun: Boolean = false
)

/**
 * Parses ESPN's league activity feed.
 *
 * FIELD SEMANTICS CHANGE WITH messageTypeId. Verified 2026-09-05 against
 * waiverProcessStatus timestamps and known transactions:
 *
 *   178 ADD free agent      to = TEAM id
 *   179 DROP with a 178     to = TEAM id
 *   180 ADD waiver claim    to = TEAM id, from = 0
 *   181 DROP with a 180     to = TEAM id
 *   188 LINEUP move         for = TEAM id, from/to = LINEUP SLOT ids
 *   230 TRADE               from/to = TEAM ids, no `for`
 *   239 DROP standalone     for = TEAM id, from = slot, to = -1
 *
 * The project handoff's "to == -1 means DROP, from == -1 means ADD" holds
 * ONLY for 239. Applying it broadly mislabels almost every message.
 */
object ActivityLog {

    const val FILTER =
        """{"topics":{"filterType":{"value":["ACTIVITY_TRANSACTIONS"]},""" +
            """"limit":150,"sortMessageDate":{"sortPriority":1,"sortAsc":false}}}"""

    fun parse(raw: String): List<Transaction> {
        val topics = JSONObject(raw).optJSONArray("topics") ?: return emptyList()
        val out = mutableListOf<Transaction>()

        for (t in 0 until topics.length()) {
            val topic = topics.optJSONObject(t) ?: continue
            val topicId = topic.optString("id", "t$t")
            val messages = topic.optJSONArray("messages") ?: continue

            for (m in 0 until messages.length()) {
                val msg = messages.optJSONObject(m) ?: continue
                val type = msg.optInt("messageTypeId", -1)
                val player = msg.optInt("targetId", 0)
                if (player == 0) continue

                val forVal = if (msg.isNull("for")) null else msg.optInt("for")
                val fromVal = if (msg.isNull("from")) null else msg.optInt("from")
                val toVal = if (msg.isNull("to")) null else msg.optInt("to")
                val date = msg.optLong("date", topic.optLong("date", 0L))
                val id = "$topicId:${msg.optString("id", m.toString())}"

                val tx = when (type) {
                    178 -> row(id, TxKind.ADD, date, toVal, null, player, null, null)
                    179 -> row(id, TxKind.DROP, date, toVal, null, player, null, null)
                    180 -> row(id, TxKind.WAIVER_ADD, date, toVal, null, player, null, null)
                    // 181 is the drop half of a PROCESSED claim, so its
                    // timestamp is a waiver run — the best signal we have
                    // for when waivers actually execute.
                    181 -> row(id, TxKind.DROP, date, toVal, null, player, null, null,
                        waiverRun = true)
                    188 -> row(id, TxKind.LINEUP, date, forVal, null, player, fromVal, toVal)
                    // Both legs appear as separate messages, so record the
                    // receiving side and note who it came from.
                    230 -> row(id, TxKind.TRADE, date, toVal, fromVal, player, null, null)
                    239 -> row(id, TxKind.DROP, date, forVal, null, player, fromVal, null)
                    else -> row(id, TxKind.UNKNOWN, date, forVal ?: toVal, null,
                        player, fromVal, toVal)
                }
                if (tx != null) out.add(tx)
            }
        }
        return out.sortedByDescending { it.whenMillis }
    }

    private fun row(
        id: String, kind: TxKind, date: Long, team: Int?,
        counterparty: Int?, player: Int, from: Int?, to: Int?,
        waiverRun: Boolean = false
    ): Transaction? {
        if (team == null || team < 0) return null
        return Transaction(
            id, kind, date, team, counterparty, player, from, to,
            isWaiverRun = waiverRun
        )
    }

    /**
     * A type-230 message says an offer was made, not that it was accepted.
     * The only reliable test is where the player sits now: if he is on the
     * team that was supposed to receive him, it went through.
     */
    fun tradeState(tx: Transaction, ownerOf: Map<Int, Int>): TradeState {
        if (tx.kind != TxKind.TRADE) return TradeState.UNKNOWN
        val ownerNow = ownerOf[tx.playerId] ?: return TradeState.UNKNOWN
        return if (ownerNow == tx.teamId) TradeState.COMPLETED
        else TradeState.NOT_COMPLETED
    }

    /** ESPN encodes team defenses as -16000 - proTeamId. */
    fun isDefense(playerId: Int) = playerId < 0

    fun defenseProTeamId(playerId: Int) = -(playerId + 16000)
}
