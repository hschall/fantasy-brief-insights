package com.aviato.fantasybrief

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.aviato.fantasybrief.data.Brief
import com.aviato.fantasybrief.data.BriefRepository
import com.aviato.fantasybrief.data.EspnClient
import com.aviato.fantasybrief.data.FlagType
import com.aviato.fantasybrief.data.LeagueRef
import com.aviato.fantasybrief.data.LeagueRegistry
import com.aviato.fantasybrief.data.LiveScoreRepository
import com.aviato.fantasybrief.data.EspnWriteClient
import com.aviato.fantasybrief.data.LiveScoreboard
import com.aviato.fantasybrief.data.RosterPlayer
import com.aviato.fantasybrief.data.WirePlayer
import com.aviato.fantasybrief.data.SecretStore
import com.aviato.fantasybrief.data.Tier
import com.aviato.fantasybrief.ui.Fb
import com.aviato.fantasybrief.ui.LeaguePicker
import com.aviato.fantasybrief.ui.LeagueScreen
import com.aviato.fantasybrief.ui.AcquireSheet
import com.aviato.fantasybrief.ui.LineupSheet
import com.aviato.fantasybrief.ui.LoginScreen
import com.aviato.fantasybrief.ui.MatchupScreen
import com.aviato.fantasybrief.ui.PlayerFocus
import com.aviato.fantasybrief.ui.PlayerSheet
import com.aviato.fantasybrief.ui.ProbeScreen
import com.aviato.fantasybrief.ui.RosterScreen
import com.aviato.fantasybrief.ui.ScorecardScreen
import com.aviato.fantasybrief.ui.SettingsScreen
import com.aviato.fantasybrief.ui.TodayScreen
import com.aviato.fantasybrief.ui.WireScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aviato.fantasybrief.data.ScheduledAdd
import com.aviato.fantasybrief.data.ScheduledAddStore
import com.aviato.fantasybrief.data.ScheduledAddWorker
import com.aviato.fantasybrief.data.TxKind
import com.aviato.fantasybrief.data.WaiverClock
import com.aviato.fantasybrief.ui.ClaimsScreen
import androidx.compose.foundation.clickable
import com.aviato.fantasybrief.ui.FloatingNavBar
import com.aviato.fantasybrief.ui.NavIcon
import com.aviato.fantasybrief.data.WaiverWatchStore
import com.aviato.fantasybrief.data.WaiverWatchWorker
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import com.aviato.fantasybrief.ui.ScreenStates
import com.aviato.fantasybrief.ui.TeamLogo
import com.aviato.fantasybrief.data.ScheduledAddSweepWorker
import com.aviato.fantasybrief.data.BriefUploader
import com.aviato.fantasybrief.data.InsightsApi
import com.aviato.fantasybrief.data.InsightPayload
import androidx.compose.runtime.mutableStateMapOf

// Seeded once on first launch so an existing install keeps working.
private const val SEED_LEAGUE_ID = 1237544639L
private const val CURRENT_SEASON = 2026

/** How long a cached Brief is served on a league switch. */
private const val CACHE_MILLIS = 10L * 60 * 1000

private enum class Tab(val label: String) {
    TODAY("Today"), MATCHUP("Matchup"), ROSTER("Team"), WIRE("Wire")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SecretStore(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Fb.Teal, background = Fb.Ground, surface = Fb.Ground,
                    onBackground = Fb.Ink, onSurface = Fb.Ink
                )
            ) {
                var signedIn by remember { mutableStateOf(store.hasCredentials) }
                if (!signedIn) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        ) {
                            LoginScreen(onCookiesFound = { s2, swid ->
                                store.save(s2, swid); signedIn = true
                            })
                        }
                    }
                } else {
                    SignedInApp(store) { store.clear(); signedIn = false }
                }
            }
        }
    }
}

/** Which league you're looking at, and how many there are. */
/**
 * Boot screen. Real progress from the repository's phases, not a timer.
 *
 * Shown only until the first brief exists; after that the pull indicator is
 * the refresh signal and this never appears again, so there are never two
 * indicators on screen at once.
 */
@Composable
private fun BootScreen(progress: Float, label: String, league: String?) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress,
        animationSpec = androidx.compose.animation.core.tween(
            // Quick on the final leg: the last stretch should read as
            // completion rather than another slow phase.
            durationMillis = if (progress >= 1f) 200 else 350
        ),
        label = "boot"
    )
    Column(
        Modifier.fillMaxSize().background(Fb.Ground).padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "FANTASY BRIEF", color = Fb.Ink, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp
        )
        league?.let {
            Text(it, color = Fb.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(Fb.Rule)
        ) {
            Box(
                Modifier.fillMaxWidth(animated.coerceIn(0.02f, 1f))
                    .fillMaxHeight().background(Fb.Teal)
            )
        }
        Text(
            label, color = Fb.Faint, fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun LeagueStrip(
    leagues: List<LeagueRef>,
    currentPage: Int,
    onSettings: (() -> Unit)? = null,
    /** Increments on a successful refresh. */
    pulseKey: Int = 0,
    /** The last key already pulsed for, owned by the caller. */
    lastPulsedKey: Int = 0,
    myLogoUrl: String? = null,
    onPulsed: (Int) -> Unit = {}
) {
    // Teal, not green: green means "beat projection" everywhere else in the
    // app, and a fetch succeeding is not that. Teal is already the structural
    // "this is live" colour.
    var pulsing by remember { mutableStateOf(false) }
    LaunchedEffect(pulseKey) {
        if (pulseKey == 0 || pulseKey == lastPulsedKey) return@LaunchedEffect
        onPulsed(pulseKey)
        pulsing = true
        kotlinx.coroutines.delay(140)
        pulsing = false
    }
    val pulse by animateColorAsState(
        targetValue = if (pulsing) Fb.Teal.copy(alpha = 0.30f) else Fb.Navy,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (pulsing) 120 else 750
        ),
        label = "refreshPulse"
    )
    Row(
        Modifier.fillMaxSize().let { Modifier }
            .fillMaxWidth()
            .background(pulse)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamLogo(
            myLogoUrl,
            leagues.getOrNull(currentPage)?.teamName ?: "?",
            20.dp
        )
        Spacer(Modifier.width(7.dp))
        Text(
            leagues.getOrNull(currentPage)?.teamName
                ?: leagues.getOrNull(currentPage)?.name ?: "",
            color = Fb.Teal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        onSettings?.let {
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { it() },
                contentAlignment = Alignment.Center
            ) { Text("\u2699", color = Fb.Muted, fontSize = 16.sp) }
            Spacer(Modifier.width(4.dp))
        }
        leagues.forEachIndexed { i, _ ->
            Box(
                Modifier.padding(start = 5.dp).size(if (i == currentPage) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (i == currentPage) Fb.Teal else Fb.Faint)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("swipe", color = Fb.Faint, fontSize = 9.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInApp(store: SecretStore, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BriefRepository(context, store) }
    val scoreRepo = remember { LiveScoreRepository(EspnClient(store)) }

    val registry = remember { LeagueRegistry(context) }
    // Existing installs already have history under this id; seeding keeps it.
    var leagues by remember {
        mutableStateOf(
            registry.all().ifEmpty {
                registry.add(
                    LeagueRef(SEED_LEAGUE_ID, CURRENT_SEASON, "Loading...", null, null)
                )
                registry.all()
            }
        )
    }
    var activeLeague by remember { mutableStateOf(registry.active()) }
    var showPicker by remember { mutableStateOf(false) }

    var tab by remember { mutableStateOf(Tab.TODAY) }
    var showProbe by remember { mutableStateOf(false) }
    val scheduledAdds = remember { ScheduledAddStore(context) }
    var showScorecard by remember { mutableStateOf(false) }
    var showClaims by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var scheduledList by remember { mutableStateOf(scheduledAdds.all()) }
    var brief by remember { mutableStateOf<Brief?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Hoisted so a player tapped on any tab opens the same sheet.
    var focused by remember { mutableStateOf<PlayerFocus?>(null) }
    var live by remember { mutableStateOf<LiveScoreboard?>(null) }
    var scoresLoading by remember { mutableStateOf(false) }
    var movingPlayer by remember { mutableStateOf<RosterPlayer?>(null) }
    var writeBusy by remember { mutableStateOf(false) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var inkSheet by remember { mutableStateOf(false) }
    var swapPreselect by remember { mutableStateOf<RosterPlayer?>(null) }
    var acquiring by remember { mutableStateOf<WirePlayer?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Bumped on every successful fetch; the strip watches it and pulses.
    var refreshPulse by remember { mutableIntStateOf(0) }
    var lastPulsed by remember { mutableIntStateOf(0) }
    // One scroll position per league, living above the tab switch so it
    // survives leaving and returning to Today.
    val screens = remember { ScreenStates() }
    val uploader = remember { BriefUploader(context) }
    val insightsApi = remember { InsightsApi(SecretStore(context)) }
    val insightsCache = remember { mutableStateMapOf<String, InsightPayload>() }
    // Guards against a league being fetched twice concurrently.
    var warming by remember { mutableStateOf(setOf<String>()) }
    // Boot progress. Only meaningful before the first brief exists;
    // after that the pull indicator covers refreshes.
    // Monotonic: two leagues report into this, and the background one
    // starts at 5% while the active one is nearly done. A boot bar that
    // moves backwards is worse than no bar, so every write takes a max.
    var bootProgressRaw by remember { mutableStateOf(0f) }
    // Monotonic: boot progress only ever moves forward. Two leagues report
    // into this and the background one starts over while the active one is
    // nearly done.
    var bootLabel by remember { mutableStateOf("Starting") }
    var bootDone by remember { mutableIntStateOf(0) }

    // Per-league cache so switching back doesn't re-run six requests. Lives
    // only for the process — a Brief is a snapshot of live state, and serving
    // a stale one at launch would be worse than waiting.
    val briefCache = remember { mutableMapOf<String, Pair<Brief, Long>>() }
    val liveCache = remember { mutableMapOf<String, LiveScoreboard>() }

    // Warm EVERY league at once rather than the active one first and the
    // others afterwards. They are independent network calls, so running them
    // concurrently costs the same wall-clock time as one of them, and the
    // first swipe lands on a rendered screen.
    //
    // repo.load is idempotent — appending events and recording an observation
    // are both no-ops on repeat — so loading a league twice cannot corrupt
    // anything.
    fun prefetchOthers(includeActive: Boolean = false) {
        val current = activeLeague?.key
        leagues
            .filter { includeActive || it.key != current }
            .forEach { ref ->
                val cached = briefCache[ref.key]
                if (cached != null &&
                    System.currentTimeMillis() - cached.second < CACHE_MILLIS
                ) return@forEach
                if (ref.key in warming) return@forEach
                warming = warming + ref.key
                scope.launch {
                    // prefetchOthers is what actually runs at boot, so the progress
                    // callback has to be here — refresh() is a different path.
                    val out = repo.load(ref.season, ref.leagueId) { f, label ->
                        val share = 1f / maxOf(1, leagues.size)
                        bootProgressRaw = maxOf(
                            bootProgressRaw,
                            (bootDone * share + f * share).coerceIn(0f, 1f)
                        )
                        bootLabel = label
                    }
                    if (out is BriefRepository.Outcome.Ok) {
                        briefCache[ref.key] = out.brief to System.currentTimeMillis()
                        uploader.maybeUpload(out.brief, liveCache[ref.key])
                        withContext(Dispatchers.IO) {
                            insightsApi.fetchInsights(ref.leagueId)
                        }?.let { insightsCache[ref.key] = it }
                        // Only the active page reads `brief` directly; the
                        // cache write above is what the others render from.
                        if (ref.key == activeLeague?.key) {
                            brief = out.brief
                            error = null
                        }
                        scoreRepo.load(
                            ref.season, ref.leagueId,
                            out.brief.league.settings.scoringPeriodId
                        )?.let { liveCache[ref.key] = it }
                    } else if (ref.key == activeLeague?.key) {
                        error = (out as? BriefRepository.Outcome.Failed)?.message
                    }
                    warming = warming - ref.key
                    if (ref.key == activeLeague?.key) loading = false
                }
            }
    }

    fun refresh(force: Boolean = true, silent: Boolean = false) {
        val ref = activeLeague ?: return

        // Serve from cache when it's fresh enough and this isn't an explicit
        // Refresh press. The window is short because waiver processing and
        // NFL news are what this app exists to catch.
        // Cache-first even on a forced refresh, so a page never has to
        // fall back to `brief` and swap object identity later.
        if (!force) {
            val cached = briefCache[ref.key]
            if (cached != null &&
                System.currentTimeMillis() - cached.second < CACHE_MILLIS
            ) {
                brief = cached.first
                live = liveCache[ref.key]
                loading = false
                error = null
                return
            }
        }

        // A background reconcile must not flash a spinner over a view that
        // is already showing the right thing.
        if (!silent) loading = true
        error = null
        scope.launch {
            when (val out = repo.load(ref.season, ref.leagueId) { f, label ->
                bootProgressRaw = maxOf(bootProgressRaw, f)
                bootLabel = label
            }) {
                is BriefRepository.Outcome.Ok -> {
                    brief = out.brief
                    briefCache[ref.key] = out.brief to System.currentTimeMillis()
                    uploader.maybeUpload(out.brief, liveCache[ref.key])
                    withContext(Dispatchers.IO) {
                        insightsApi.fetchInsights(ref.leagueId)
                    }?.let { insightsCache[ref.key] = it }
                    // Every path that refreshes should say so — the pull
                    // gesture went through a different caller than the
                    // button and was silent.
                    if (!silent) refreshPulse++
                    prefetchOthers()
                    // Fill in the real name and team once we've seen them.
                    val team = out.brief.league.myTeam
                    if (ref.name != out.brief.league.settings.name ||
                        ref.teamId != team?.id
                    ) {
                        registry.rename(
                            ref.leagueId, ref.season,
                            out.brief.league.settings.name, team?.name, team?.id
                        )
                        leagues = registry.all()
                        activeLeague = registry.active()
                    }
                }
                is BriefRepository.Outcome.Failed -> error = out.message
            }
            loading = false
        }
    }

    // Keyed on the active league, so switching refetches. Clearing brief first
    // prevents one league's roster flashing under another league's masthead.
    // One request, no storage writes, no observation history touched.
    // Safe to press as often as you like during a game.
    fun refreshScores() {
        val ref = activeLeague ?: return
        val week = brief?.league?.settings?.scoringPeriodId ?: return
        scoresLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                scoreRepo.load(ref.season, ref.leagueId, week)
            }
            if (result != null) {
                live = result
                liveCache[ref.key] = result
                refreshPulse++
            }
            scoresLoading = false
        }
    }


    LaunchedEffect(activeLeague?.key) {
        val key = activeLeague?.key
        val cached = key?.let { briefCache[it] }
        if (cached != null &&
            System.currentTimeMillis() - cached.second < CACHE_MILLIS
        ) {
            brief = cached.first
            live = liveCache[key]
            loading = false
            // Team and Matchup read the same live data; whichever you open
            // first should have it.
            if (liveCache[key] == null) refreshScores()
        } else {
            // Show the stale brief rather than nothing: the boot screen is
            // for having no data at all, not for having slightly old data.
            val stale = key?.let { briefCache[it] }?.first
            brief = stale
            live = liveCache[key]
            if (stale == null) bootProgressRaw = 0f
            loading = true
            // Every league at once, active included, so the first swipe never
            // waits. Concurrent, so this is no slower than fetching one.
            prefetchOthers(includeActive = true)
        }
    }

    val actionable = brief?.flags?.count { it.priority <= FlagType.TRADE_TARGET.rank } ?: 0
    val eliteCount = brief?.tiers?.get(Tier.ELITE)?.size ?: 0

    // Cold boot: nothing to show yet, so the whole screen is the bar.
    // Once any brief exists, refreshes use the pull indicator instead.
    var bootSettled by remember { mutableStateOf(false) }
    LaunchedEffect(brief != null) {
        // Cold start: the brief has landed but scores have not been asked for.
        // Team and Matchup read the same live data, so whichever opens first
        // should have it.
        if (brief != null && liveCache[activeLeague?.key] == null) refreshScores()
        if (brief != null && !bootSettled) {
            bootProgressRaw = 1f
            bootLabel = "Ready"
            // Must exceed the bar's animation duration, or the screen
            // swaps while it is still filling.
            kotlinx.coroutines.delay(420)
            bootSettled = true
        }
    }

    // Only when there is nothing to show. A refresh over existing data
    // uses the pull indicator and the strip pulse instead.
    if ((brief == null || !bootSettled) && error == null &&
        (loading || brief != null)
    ) {
        BootScreen(
            bootProgressRaw, bootLabel,
            if (leagues.size > 1) "Loading ${leagues.size} leagues"
            else activeLeague?.name
        )
        return
    }

    Scaffold(
        containerColor = Fb.Ground,
        bottomBar = {
            FloatingNavBar(
                items = listOf(
                    Triple(NavIcon.TODAY, "Today", actionable),
                    Triple(NavIcon.MATCHUP, "Matchup", 0),
                    Triple(NavIcon.TEAM, "Team", 0),
                    Triple(NavIcon.WIRE, "Wire", eliteCount)
                ),
                selectedIndex = Tab.entries.indexOf(tab),
                bottomInset = 0.dp,
                onSelect = { tab = Tab.entries[it] }
            )
        }
    ) { padding ->
        val inset = padding.calculateBottomPadding()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            when (tab) {
                Tab.TODAY -> {
                    val todayPager = rememberPagerState(
                        initialPage = leagues.indexOfFirst { it.key == activeLeague?.key }
                            .coerceAtLeast(0),
                        pageCount = { maxOf(1, leagues.size) }
                    )
                    LaunchedEffect(todayPager.settledPage) {
                        leagues.getOrNull(todayPager.settledPage)?.let { ref ->
                            if (ref.key != activeLeague?.key) {
                                registry.setActive(ref)
                                activeLeague = ref
                            }
                        }
                    }
                    LaunchedEffect(activeLeague?.key) {
                        val i = leagues.indexOfFirst { it.key == activeLeague?.key }
                        // settledPage, not currentPage: mid-swipe currentPage
                        // has already moved, so this fired against the gesture
                        // and fought it.
                        if (i >= 0 && i != todayPager.settledPage &&
                            !todayPager.isScrollInProgress
                        ) {
                            todayPager.scrollToPage(i)
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        if (leagues.size > 1) {
                            LeagueStrip(leagues, todayPager.currentPage, { showSettings = true }, refreshPulse,
                            lastPulsed, brief?.league?.myTeam?.logoUrl) { lastPulsed = it }
                        }
                        HorizontalPager(
                            state = todayPager, modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val ref = leagues.getOrNull(page)
                            val isActive = ref?.key == activeLeague?.key
                            // Read the SAME source whether active or not.
                            // Switching between `brief` and the cache on settle
                            // handed Compose a different object for identical
                            // data, which redrew the page.
                            val pageBrief = ref?.let { briefCache[it.key]?.first }
                                ?: if (isActive) brief else null
                            PullToRefreshBox(
                                isRefreshing = isActive && loading,
                                onRefresh = { if (isActive) refresh(force = true) },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                TodayScreen(
                                    brief = pageBrief,
                                    loading = isActive && loading && pageBrief == null,
                                    error = if (isActive) error else null,
                                    bottomInset = inset,
                                    onRefresh = {
                            if (isActive) { refresh(force = true); toast = "Updated" }
                        },
                                    onResetSnapshot = {
                                        activeLeague?.let {
                                            repo.resetSnapshot(it.season, it.leagueId)
                                            briefCache.remove(it.key)
                                        }
                                        refresh(force = true)
                                    },
                                    onSwitchLeague = { showPicker = true },
                                    onPlayer = { focused = it },
                                    remoteInsights = insightsCache[ref?.key],
                                    onMovePlayer = if (isActive) {
                                        { movingPlayer = it; swapPreselect = null
                                            writeError = null; inkSheet = true }
                                    } else null,
                                    onSwapTo = if (isActive) {
                                        { subject, target ->
                                            movingPlayer = subject
                                            swapPreselect = target
                                            writeError = null; inkSheet = true
                                        }
                                    } else null,
                                    onOpenMatchup = { tab = Tab.MATCHUP },
                                    onOpenWire = { tab = Tab.WIRE },
                                    onAcquire = { acquiring = it; writeError = null },
                                    listState = screens.forLeague(
                                        ref?.key ?: "none"
                                    ).todayScroll,
                                    state = screens.forLeague(ref?.key ?: "none"),
                                    global = screens.global
                                )
                            }
                        }
                    }
                }
                Tab.MATCHUP -> if (leagues.size > 1) {
                    val activeIndex = leagues.indexOfFirst { it.key == activeLeague?.key }
                        .coerceAtLeast(0)
                    val pager = rememberPagerState(
                        initialPage = activeIndex, pageCount = { leagues.size }
                    )

                    // Swipe settles -> switch league. Guarded so the picker
                    // and the pager cannot fight each other.
                    LaunchedEffect(pager.settledPage) {
                        leagues.getOrNull(pager.settledPage)?.let { ref ->
                            if (ref.key != activeLeague?.key) {
                                registry.setActive(ref)
                                activeLeague = ref
                            }
                            refreshScores()
                        }
                    }
                    // Picker changes the league -> pager follows.
                    LaunchedEffect(activeLeague?.key) {
                        val i = leagues.indexOfFirst { it.key == activeLeague?.key }
                        if (i >= 0 && i != pager.settledPage &&
                            !pager.isScrollInProgress
                        ) pager.scrollToPage(i)
                    }

                    Column(Modifier.fillMaxSize()) {
                        LeagueStrip(leagues, pager.currentPage, { showSettings = true }, refreshPulse,
                            lastPulsed, brief?.league?.myTeam?.logoUrl) { lastPulsed = it }
                        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                            val ref = leagues[page]
                            val isActive = ref.key == activeLeague?.key
                            val pageBrief = briefCache[ref.key]?.first
                                ?: if (isActive) brief else null
                            MatchupScreen(
                                brief = pageBrief,
                                loading = isActive && loading && pageBrief == null,
                                live = if (isActive) live else liveCache[ref.key],
                                scoresLoading = isActive && scoresLoading,
                                onRefreshScores = { if (isActive) refreshScores() },
                                onRefreshAll = { if (isActive) refreshScores() },
                                selectedWeek = screens.forLeague(ref.key).selectedWeek,
                                onSelectWeek = {
                                    screens.forLeague(ref.key).selectedWeek = it
                                },
                                bottomInset = inset,
                                onPlayer = { focused = it },
                                onMovePlayer = if (isActive) {
                                    { movingPlayer = it; writeError = null; inkSheet = true }
                                } else null
                            )
                        }
                    }
                } else MatchupScreen(
                    brief, loading, live, scoresLoading,
                    onRefreshScores = { refreshScores() },
                    // Pull-down, the Scores button and entering the tab all
                    // do the same thing: two requests, nothing else.
                    onRefreshAll = { refreshScores() },
                    selectedWeek = screens.forLeague(
                        activeLeague?.key ?: "none"
                    ).selectedWeek,
                    onSelectWeek = {
                        screens.forLeague(activeLeague?.key ?: "none")
                            .selectedWeek = it
                    },
                    bottomInset = inset,
                    onPlayer = { focused = it },
                    onMovePlayer = { movingPlayer = it; writeError = null; inkSheet = true }
                )

                Tab.ROSTER -> if (leagues.size > 1) {
                    val rosterPager = rememberPagerState(
                        initialPage = leagues.indexOfFirst { it.key == activeLeague?.key }
                            .coerceAtLeast(0),
                        pageCount = { leagues.size }
                    )
                    LaunchedEffect(rosterPager.settledPage) {
                        leagues.getOrNull(rosterPager.settledPage)?.let { ref ->
                            if (ref.key != activeLeague?.key) {
                                registry.setActive(ref)
                                activeLeague = ref
                            }
                        }
                    }
                    LaunchedEffect(activeLeague?.key) {
                        val i = leagues.indexOfFirst { it.key == activeLeague?.key }
                        if (i >= 0 && i != rosterPager.settledPage &&
                            !rosterPager.isScrollInProgress
                        ) rosterPager.scrollToPage(i)
                    }

                    HorizontalPager(
                        state = rosterPager, modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val ref = leagues[page]
                        val isActive = ref.key == activeLeague?.key
                        val pageBrief = briefCache[ref.key]?.first
                            ?: if (isActive) brief else null
                        PullToRefreshBox(
                            isRefreshing = isActive && loading,
                            onRefresh = { if (isActive) refresh(force = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            RosterScreen(
                                brief = pageBrief,
                                loading = isActive && loading && pageBrief == null,
                                live = if (isActive) live else liveCache[ref.key],
                                bottomInset = inset,
                                onPlayer = { focused = it },
                                lockedPlayerIds = insightsCache[ref.key]?.items
                                    ?.filter { it.kind.equals("LOCK", true) }
                                    ?.mapNotNull { it.playerId }?.toSet().orEmpty(),
                                onMovePlayer = {
                                    if (isActive) {
                                        movingPlayer = it; writeError = null; inkSheet = false
                                    }
                                },
                                onRefresh = { if (isActive) refresh(force = true) },
                                state = screens.forLeague(ref.key),
                                header = { LeagueStrip(leagues, rosterPager.currentPage, { showSettings = true }, refreshPulse,
                            lastPulsed, brief?.league?.myTeam?.logoUrl) { lastPulsed = it } }
                            )
                        }
                    }
                } else RosterScreen(
                    brief, loading, live, inset,
                    onPlayer = { focused = it },
                    onMovePlayer = { movingPlayer = it; writeError = null; inkSheet = false },
                    state = screens.forLeague(activeLeague?.key ?: "none"),
                    lockedPlayerIds = insightsCache[activeLeague?.key]?.items
                        ?.filter { it.kind.equals("LOCK", true) }
                        ?.mapNotNull { it.playerId }?.toSet().orEmpty()
                )
                Tab.WIRE -> if (showClaims) {
                    ClaimsScreen(
                        brief = brief,
                        scheduled = scheduledList.filter {
                            it.leagueId == (activeLeague?.leagueId ?: 0L)
                        },
                        bottomInset = inset,
                        onCancel = { item ->
                            ScheduledAddWorker.cancel(context, item.id)
                            scheduledAdds.remove(item.id)
                            scheduledList = scheduledAdds.all()
                            toast = "Cancelled"
                        },
                        onBack = { showClaims = false }
                    )
                } else if (leagues.size > 1) {
                    val wirePager = rememberPagerState(
                        initialPage = leagues.indexOfFirst { it.key == activeLeague?.key }
                            .coerceAtLeast(0),
                        pageCount = { leagues.size }
                    )
                    LaunchedEffect(wirePager.settledPage) {
                        leagues.getOrNull(wirePager.settledPage)?.let { ref ->
                            if (ref.key != activeLeague?.key) {
                                registry.setActive(ref); activeLeague = ref
                            }
                        }
                    }
                    LaunchedEffect(activeLeague?.key) {
                        val i = leagues.indexOfFirst { it.key == activeLeague?.key }
                        if (i >= 0 && i != wirePager.settledPage &&
                            !wirePager.isScrollInProgress
                        ) wirePager.scrollToPage(i)
                    }
                    Column(Modifier.fillMaxSize()) {
                        LeagueStrip(leagues, wirePager.currentPage, { showSettings = true }, refreshPulse,
                            lastPulsed, brief?.league?.myTeam?.logoUrl) { lastPulsed = it }
                        HorizontalPager(
                            state = wirePager, modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val ref = leagues[page]
                            val isActive = ref.key == activeLeague?.key
                            val pageBrief = briefCache[ref.key]?.first
                                ?: if (isActive) brief else null
                            PullToRefreshBox(
                                isRefreshing = isActive && loading,
                                onRefresh = { if (isActive) refresh(force = true) },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                WireScreen(
                                    brief = pageBrief,
                                    loading = isActive && loading && pageBrief == null,
                                    bottomInset = inset,
                                    onPlayer = { focused = it },
                                    onAcquire = { acquiring = it; writeError = null },
                                    inFlightCount = scheduledList.count {
                                        it.status == "PENDING" && it.leagueId == ref.leagueId
                                    },
                                    onOpenClaims = {
                                        scheduledList = scheduledAdds.all()
                                        showClaims = true
                                    },
                                    onRefresh = { if (isActive) refresh(force = true) },
                                    state = screens.forLeague(ref.key),
                                    global = screens.global
                                )
                            }
                        }
                    }
                } else WireScreen(
                    brief = brief,
                    loading = loading,
                    bottomInset = inset,
                    onPlayer = { focused = it },
                    onAcquire = { acquiring = it; writeError = null },
                    inFlightCount = scheduledList.count {
                        it.status == "PENDING" &&
                            it.leagueId == (activeLeague?.leagueId ?: 0L)
                    },
                    onOpenClaims = {
                        scheduledList = scheduledAdds.all(); showClaims = true
                    },
                    state = screens.forLeague(activeLeague?.key ?: "none"),
                    global = screens.global
                )
            }
        }
    }

    focused?.let { f ->
        brief?.let { b ->
            PlayerSheet(
                focus = f, brief = b,
                onAcquire = {
                    b.pool.firstOrNull { it.playerId == f.playerId }?.let { w ->
                        focused = null; acquiring = w; writeError = null
                    }
                },
                onDismiss = { focused = null }
            )
        }
    }


    if (showSettings) {
        Box(Modifier.fillMaxSize().background(Fb.Ground).statusBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                if (showProbe) {
                    ProbeScreen(store, 0.dp) { showProbe = false }
                } else if (showScorecard) {
                    ScorecardScreen(brief, 0.dp) { showScorecard = false }
                } else {
                    SettingsScreen(
                        leagueId = activeLeague?.leagueId ?: SEED_LEAGUE_ID,
                        season = activeLeague?.season ?: CURRENT_SEASON,
                        onFullRefresh = {
                            repo.clearResponseCache()
                            activeLeague?.let { briefCache.remove(it.key) }
                            live = null
                            refresh(force = true)
                        },
                        fullRefreshBusy = loading,
                        onSignOut = onSignOut,
                        onOpenProbe = { showProbe = true },
                        onOpenScorecard = { showScorecard = true },
                        onClose = { showSettings = false },
                        bottomInset = 0.dp
                    )
                }
            }
        }
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2400)
            toast = null
        }
        // Bottom, above the nav bar: the thumb is already there and it does not
        // cover the header you just refreshed.
        //
        // Neutral rather than a hue. The palette already spends seven colours
        // on real meanings — tier, performance, waivers, the gold alarm — and
        // an eighth for something that says only "done" would make every other
        // one mean slightly less.
        Box(
            Modifier.fillMaxSize().navigationBarsPadding()
                .padding(bottom = 84.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF1A2634))
                    .border(1.dp, Fb.Rule, RoundedCornerShape(7.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(message, color = Fb.Ink, fontSize = 12.sp)
            }
        }
    }

    acquiring?.let { target ->
        brief?.let { b ->
            AcquireSheet(
                target = target, brief = b, busy = writeBusy, lastError = writeError,
                onSubmit = { addId, dropId, isClaim ->
                    val ref = activeLeague ?: return@AcquireSheet
                    val team = b.league.myTeam ?: return@AcquireSheet
                    writeBusy = true; writeError = null
                    scope.launch {
                        val client = EspnWriteClient(store)
                        val res = withContext(Dispatchers.IO) {
                            client.addPlayer(
                                ref.season, ref.leagueId, team.id,
                                b.league.settings.scoringPeriodId,
                                addPlayerId = addId, dropPlayerId = dropId,
                                isWaiverClaim = isClaim, dryRun = false
                            )
                        }
                        writeBusy = false
                        if (res.ok) {
                            toast = if (isClaim)
                                "Claim submitted for ${target.name}"
                            else "${target.name} added"
                            acquiring = null

                            // Optimistic update. We know exactly what changed,
                            // so show it now rather than waiting on a read API
                            // that may take a minute to catch up.
                            val added = b.wire.firstOrNull { it.playerId == addId }
                            brief?.let { current ->
                                val updated = current.league.copy(
                                    teams = current.league.teams.map { t ->
                                        if (t.id != team.id) t else t.copy(
                                            roster = t.roster
                                                .filterNot { it.playerId == dropId } +
                                                listOfNotNull(added?.let { w ->
                                                    RosterPlayer(
                                                        playerId = w.playerId,
                                                        name = w.name,
                                                        positionId = w.positionId,
                                                        proTeamId = w.proTeamId,
                                                        lineupSlotId = 20,
                                                        injuryStatus = w.injuryStatus,
                                                        injured = w.injured,
                                                        projection = w.projection,
                                                        seasonActual = null,
                                                        gamesPlayed = 0,
                                                        weeklyActuals = emptyMap(),
                                                        percentOwned = w.percentOwned,
                                                        percentChange = w.percentChange,
                                                        eligibleSlots = listOf(20),
                                                        acquisitionType = "ADD",
                                                        acquisitionMillis =
                                                            System.currentTimeMillis()
                                                    )
                                                })
                                        )
                                    }
                                )
                                brief = current.copy(league = updated)
                            }

                            // Then reconcile. Delayed, because refreshing
                            // immediately just re-reads the stale roster and
                            // overwrites the optimistic view with old data.
                            scope.launch {
                                kotlinx.coroutines.delay(4000)
                                briefCache.remove(ref.key)
                                refresh(force = true, silent = true)
                            }
                        } else {
                            writeError = client.errorMessage(res)
                        }
                    }
                },
                onSchedule = { addId, dropId, firesAt ->
                    val ref = activeLeague ?: return@AcquireSheet
                    val drop = b.league.myTeam?.roster
                        ?.firstOrNull { it.playerId == dropId }
                    val item = ScheduledAdd(
                        id = "${ref.leagueId}-$addId-${System.currentTimeMillis()}",
                        leagueId = ref.leagueId, season = ref.season,
                        addPlayerId = addId, addPlayerName = target.name,
                        dropPlayerId = dropId,
                        dropPlayerName = drop?.name ?: "player $dropId",
                        firesAtMillis = firesAt,
                        createdAtMillis = System.currentTimeMillis()
                    )
                    scheduledAdds.add(item)
                    ScheduledAddWorker.schedule(context, item)

                    // Record the prediction against reality: this polls until
                    // the player actually goes free, which is the only way to
                    // learn the real cadence.
                    val dropTx = b.transactions.firstOrNull {
                        it.playerId == addId && it.kind == TxKind.DROP
                    }
                    WaiverWatchStore(context).add(
                        WaiverWatchStore.Watch(
                            leagueId = ref.leagueId, season = ref.season,
                            playerId = addId, playerName = target.name,
                            droppedAtMillis = dropTx?.whenMillis ?: 0L,
                            predictedMillis = firesAt,
                            firstSeenFreeMillis = null,
                            lastCheckedMillis = 0L, checks = 0
                        )
                    )
                    WaiverWatchWorker.start(context)
                    ScheduledAddSweepWorker.start(context)
                    scheduledList = scheduledAdds.all()
                    acquiring = null
                    toast = "Scheduled: ${target.name} when he clears"
                },
                // VERIFIED 2026-09-10: ESPN publishes waiverProcessDate per
                // player, and it matches what we measured by observation
                // (01:00 against an observed 01:04 and 01:19).
                //
                // The old chain derived this from a DROP EVENT in the activity
                // log, which meant it only worked for players you had dropped
                // yourself — anyone else on waivers had no anchor and the
                // schedule option silently disappeared. Three layers of
                // inference replaced by one field that was in the response all
                // along.
                clearsAt = target.waiverProcessMillis.takeIf { it > 0 }
                    ?: b.transactions
                        .filter { it.playerId == target.playerId && it.kind == TxKind.DROP }
                        .maxByOrNull { it.whenMillis }
                        ?.let { drop ->
                            WaiverClock.clearsAt(
                                drop.whenMillis,
                                b.league.settings.waiverProcessDays,
                                b.league.settings.waiverProcessHour,
                                b.league.settings.waiverHours
                            )
                        },
                onDismiss = { acquiring = null; writeError = null }
            )
        }
    }

    movingPlayer?.let { p ->
        brief?.let { b ->
            LineupSheet(
                player = p, brief = b, busy = writeBusy, lastError = writeError,
                ink = inkSheet, preselect = swapPreselect,
                onApply = { moves ->
                    val ref = activeLeague ?: return@LineupSheet
                    val team = b.league.myTeam ?: return@LineupSheet
                    writeBusy = true; writeError = null
                    scope.launch {
                        val client = EspnWriteClient(store)
                        val res = withContext(Dispatchers.IO) {
                            client.setLineup(
                                ref.season, ref.leagueId, team.id,
                                b.league.settings.scoringPeriodId,
                                moves, dryRun = false
                            )
                        }
                        writeBusy = false
                        if (res.ok) {
                            toast = "Lineup updated"
                            movingPlayer = null
                            // The cached Brief now describes a lineup that no
                            // longer exists, so force a real refetch.
                            briefCache.remove(ref.key)
                            refresh(force = true)
                        } else {
                            writeError = client.errorMessage(res)
                        }
                    }
                },
                onDismiss = { movingPlayer = null; swapPreselect = null
                    writeError = null }
            )
        }
    }

    if (showPicker) {
        LeaguePicker(
            leagues = leagues,
            active = activeLeague,
            defaultSeason = CURRENT_SEASON,
            onSelect = { registry.setActive(it); activeLeague = it },
            onAdd = { id, season ->
                val ref = LeagueRef(id, season, "Loading...", null, null)
                registry.add(ref); registry.setActive(ref)
                leagues = registry.all(); activeLeague = ref
            },
            onRemove = {
                registry.remove(it)
                leagues = registry.all(); activeLeague = registry.active()
            },
            onDismiss = { showPicker = false }
        )
    }
}
