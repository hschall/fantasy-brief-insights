package com.aviato.fantasybrief.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aviato.fantasybrief.data.BoardSort
import com.aviato.fantasybrief.data.TxKind

/**
 * Screen state that survives a league swipe and a tab switch.
 *
 * Everything here was previously declared inside a composable, which meant it
 * was disposed the moment you swiped away — you would come back to week 1,
 * the top of the list, and every filter reset.
 *
 * The split is deliberate:
 *
 *   PER LEAGUE — scroll positions, selected week, expansions. These are
 *   "where I was", and where you were in Chem has nothing to do with where
 *   you were in IPADE.
 *
 *   GLOBAL — the wire filters and sort. These are "what I am looking for",
 *   and if you are checking running backs you are checking them in both
 *   leagues.
 */
class LeagueScreenState {
    val todayScroll = LazyListState()
    val rosterScroll = LazyListState()
    val wireScroll = LazyListState()
    val matchupScroll = LazyListState()

    /** Shared by Matchup and Team: both answer "what week am I looking at". */
    var selectedWeek by mutableStateOf<Int?>(null)

    var standingsExpanded by mutableStateOf(false)
    var atRiskOpen by mutableStateOf(false)
    var txExpanded by mutableStateOf(false)
    var highlightTeam by mutableStateOf<Int?>(null)
    var dismissed by mutableStateOf(setOf<Int>())
    var overrideTeamId by mutableStateOf<Int?>(null)

    /** The last do-this-first headline seen, so a repeat stays collapsed. */
    var lastSeenTopAction by mutableStateOf<String?>(null)
    var doFirstOpen by mutableStateOf(true)
    /** 0 = today, 1 = insights. */
    var todayPane by mutableStateOf(0)
}

/** Filters follow you between leagues; they describe intent, not position. */
class GlobalScreenState {
    var wireTab by mutableStateOf(0)              // 0 board, 1 depth
    var wirePosition by mutableStateOf<String?>(null)
    var wireSort by mutableStateOf(BoardSort.PROJECTION)
    var wireStartersOnly by mutableStateOf(false)
    var txFilter by mutableStateOf<TxKind?>(null)
}

/** One holder for the whole app, owned above the tab switch. */
class ScreenStates {
    private val byLeague = mutableMapOf<String, LeagueScreenState>()
    val global = GlobalScreenState()

    fun forLeague(key: String): LeagueScreenState =
        byLeague.getOrPut(key) { LeagueScreenState() }
}
