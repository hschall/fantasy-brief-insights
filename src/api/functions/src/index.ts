/**
 * Fantasy Brief API.
 *
 * A pipe between the Android app and Claude, with Firestore in the middle.
 *
 *   app  --POST /brief-->    [dump]      --GET /brief-->    Claude
 *   app  <--GET /insights--  [analysis]  <--POST /insights-- Claude
 *
 * The phone cannot host anything reachable — no stable address, no inbound
 * connections, and it sleeps. So the data sits here instead, and both ends
 * poll it.
 *
 * Auth is a shared secret in an x-api-key header. Not sophisticated, but the
 * payload is fantasy football and the worst case is someone reading a roster.
 */
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";

admin.initializeApp();
const db = admin.firestore();
const API_KEY = defineSecret("FB_API_KEY");
/**
 * Read-only key, accepted as a ?key= query parameter.
 *
 * Claude's fetcher cannot set request headers, so header-only auth meant the
 * dump had to be copied by hand every time. A query parameter can be read,
 * but it also ends up in logs and history — so this key is deliberately
 * separate and can ONLY read the brief. It cannot post insights or write
 * anything, which makes a leak worth a shrug rather than a rotation.
 */
const READ_KEY = defineSecret("FB_READ_KEY");

/** Full access: the header key only. */
function authed(req: any, res: any): boolean {
  if (req.header("x-api-key") !== API_KEY.value()) {
    res.status(401).json({ error: "bad or missing x-api-key" });
    return false;
  }
  return true;
}

/** The app's league dump. One document per league, overwritten each time. */
export const brief = onRequest(
  { secrets: [API_KEY, READ_KEY], maxInstances: 2, timeoutSeconds: 30 },
  async (req, res) => {
  // A GET may authenticate with the read-only key in the query string,
  // because Claude's fetcher cannot set headers. Anything that WRITES
  // still requires the header key.
  const readOnly = req.method === "GET" && req.query.key === READ_KEY.value();
  if (!readOnly && !authed(req, res)) return;

  const leagueId = String(req.query.leagueId || req.body?.leagueId || "");
  if (!leagueId) {
    res.status(400).json({ error: "leagueId required" });
    return;
  }
  // "daily" or "weekly" — separate documents so both survive.
  const kind = String(req.query.kind || req.body?.kind || "daily");
  const docId = kind === "daily" ? leagueId : `${leagueId}_${kind}`;
  const doc = db.collection("briefs").doc(docId);

  if (req.method === "POST") {
    await doc.set({
      leagueId,
      kind,
      dump: req.body?.dump ?? "",
      week: req.body?.week ?? null,
      teamName: req.body?.teamName ?? null,
      uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    res.json({ ok: true, leagueId });
    return;
  }

  if (req.method === "GET") {
    const snap = await doc.get();
    if (!snap.exists) {
      res.status(404).json({ error: "no brief uploaded for that league yet" });
      return;
    }
    res.json(snap.data());
    return;
  }

  res.status(405).json({ error: "use GET or POST" });
});

/** Claude's analysis. Structured so the app can render it, not a blob. */
export const insights = onRequest({ secrets: [API_KEY] }, async (req, res) => {
  if (!authed(req, res)) return;

  const leagueId = String(req.query.leagueId || req.body?.leagueId || "");
  if (!leagueId) {
    res.status(400).json({ error: "leagueId required" });
    return;
  }
  const doc = db.collection("insights").doc(leagueId);

  if (req.method === "POST") {
    const items = Array.isArray(req.body?.items) ? req.body.items : [];
    await doc.set({
      leagueId,
      week: req.body?.week ?? null,
      summary: req.body?.summary ?? "",
      items,
      generatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    res.json({ ok: true, leagueId, count: items.length });
    return;
  }

  if (req.method === "GET") {
    const snap = await doc.get();
    if (!snap.exists) {
      res.json({ leagueId, items: [], summary: "", generatedAt: null });
      return;
    }
    res.json(snap.data());
    return;
  }

  res.status(405).json({ error: "use GET or POST" });
});

/**
 * Read-only proxy to ESPN's fantasy API.
 *
 * WHY THIS EXISTS: espn_s2 and SWID are session cookies for the whole ESPN
 * account, not just fantasy. Claude's sandbox cannot reach ESPN and cannot
 * set headers, so the only way for it to read the private league directly is
 * for something else to hold the cookies and make the call.
 *
 * That is a real concession, so the blast radius is deliberately small:
 *   - GET only, and only paths matching the fantasy read prefix
 *   - the cookies are never echoed, logged, or returned in any form
 *   - the read key authenticates, so this is no more exposed than the dump
 *   - no-store on the response, and a cache-busting param is honoured,
 *     because the fetcher that calls this caches by URL
 *
 * If this ever needs to do more than read a league, write a new function
 * rather than widening this one.
 */
const ESPN_S2 = defineSecret("ESPN_S2");
const ESPN_SWID = defineSecret("ESPN_SWID");

export const espn = onRequest(
  {
    secrets: [READ_KEY, ESPN_S2, ESPN_SWID],
    maxInstances: 2,
    timeoutSeconds: 30,
  },
  async (req, res) => {
    if (req.method !== "GET") {
      res.status(405).json({ error: "GET only" });
      return;
    }
    if (req.query.key !== READ_KEY.value()) {
      res.status(401).json({ error: "bad or missing key" });
      return;
    }

    // Whitelist, not a pass-through. Anything that is not a fantasy read is
    // refused outright rather than forwarded.
    const path = String(req.query.path || "");
    const ok =
      path.startsWith("/apis/v3/games/ffl/seasons/") ||
      path.startsWith("/apis/v3/games/ffl/leagueHistory/");
    if (!ok) {
      res.status(400).json({
        error: "path must start with /apis/v3/games/ffl/seasons/",
        got: path,
      });
      return;
    }

    // An x-fantasy-filter is a JSON header ESPN requires for player queries.
    const filter = req.query.filter ? String(req.query.filter) : null;
    const headers: Record<string, string> = {
      Cookie: `espn_s2=${ESPN_S2.value()}; SWID=${ESPN_SWID.value()}`,
      Accept: "application/json",
      "User-Agent": "fantasy-brief-proxy/1.0",
    };
    if (filter) headers["x-fantasy-filter"] = filter;

    try {
      const upstream = await fetch(
        `https://lm-api-reads.fantasy.espn.com${path}`,
        { headers }
      );
      const body = await upstream.text();
      res.set("Cache-Control", "no-store, max-age=0");
      // The league id is in the path we were asked to fetch.
      res.status(upstream.status).send(scrub(body, ESPN_SWID.value()));
    } catch (e: any) {
      res.status(502).json({ error: "upstream failed", detail: String(e).slice(0, 200) });
    }
  }
);


/**
 * Removes personal information ESPN includes but nothing here needs.
 *
 * mTeam carries members[] with real first and last names and a long list of
 * notification preferences for every manager in the league. Team names,
 * records and rosters are the point; the humans behind them are not, and
 * proxying their names into a chat log is not something they agreed to.
 *
 * Fails open on unparseable bodies: better to return the data than to break
 * a read because the scrub could not run.
 */
function scrub(body: string, swid: string): string {
  try {
    const d = JSON.parse(body);

    // Identify the owner's team BEFORE the owner ids are stripped. ESPN
    // stores SWID with braces in team.owners, so compare loosely.
    const norm = (v: string) => v.replace(/[^0-9a-f-]/gi, "").toLowerCase();
    const key = norm(swid);
    let mine: number | undefined;
    if (Array.isArray(d.teams)) {
      for (const t of d.teams) {
        const owners: string[] = t.owners || [];
        if (owners.some((o) => norm(String(o)) === key)) {
          mine = t.id;
          break;
        }
      }
    }
    if (mine !== undefined) d.myTeamId = mine;
    if (Array.isArray(d.members)) {
      d.members = d.members.map((m: any) => ({
        id: m.id,
        displayName: m.displayName,
      }));
    }
    if (Array.isArray(d.teams)) {
      d.teams = d.teams.map((t: any) => {
        const { owners, primaryOwner, ...rest } = t;
        return { ...rest, isMine: t.id === mine };
      });
    }
    return JSON.stringify(d);
  } catch {
    return body;
  }
}

/**
 * Publishes league state to GitHub every 15 minutes.
 *
 * WHY NOT JUST PROXY: Claude's fetcher cannot be trusted for this. Asked for
 * league 1325565673 it returned league 1237544639 from an earlier request,
 * with no error — silent substitution, not caching. Reading a file it fetched
 * itself from raw.githubusercontent.com has no such failure mode.
 *
 * So this pushes rather than waiting to be pulled. Fifteen minutes stale and
 * verifiable beats instant and quietly wrong.
 *
 * Leagues are discovered from fantasy/v2 rather than configured, so a new
 * league or a new season needs no code change.
 */
const GH_TOKEN = defineSecret("GH_TOKEN");
const GH_REPO = "hschall/fantasy-brief-insights";

const VIEWS = [
  "mTeam", "mRoster", "mSettings", "mMatchup", "mMatchupScore",
  "mPendingTransactions",
];

/**
 * The free agent pool.
 *
 * One UNFILTERED call, not a filterStatus one. An available player is a
 * market player whose status is FREEAGENT or WAIVERS, so the unfiltered pull
 * is a strict superset at the same request cost, and it carries the ownership
 * of rostered players too. This is the shape WireRepository.loadAll already
 * uses in the app, so the filter is proven in production rather than new.
 *
 * A `limit` with no valid `sort` returns 400. sortPercOwned works;
 * sortPercOwnedChange does not exist, which is why velocity is sorted here.
 */
const WIRE_FILTER = JSON.stringify({
  players: { limit: 500, sortPercOwned: { sortAsc: false, sortPriority: 1 } },
});

/**
 * Kept per league. Egress was already ~29% of the free tier at 29 KB a file,
 * and the spend cap pauses the service rather than warning. 120 rows costs
 * ~25 KB, which lands around 54%. 200 fits today but leaves no room for a
 * third league, and the way you would find out is the service stopping.
 */
const WIRE_KEEP = 120;

/**
 * Slims the free agent pool.
 *
 * NOTE THE SHAPE DIFFERENCE. On kona_player_info, `status` and
 * `waiverProcessDate` sit on the ENTRY, while everything else sits on
 * entry.player. On mRoster the player hangs off playerPoolEntry.player and
 * there is no status at all. slim() cannot be reused here, and a slimWire()
 * written by analogy to it returns empty statuses and zero clear times
 * without failing.
 */
function slimWire(d: any, period: number): any[] {
  const POS: Record<number, string> = {
    1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "DST",
  };

  const rows = (d.players || []).map((e: any) => {
    const p = e.player || {};
    const own = p.ownership || {};
    const ranks = (p.rankings?.[String(period)] || [])
      .filter((r: any) =>
        r.rankType === "PPR" && r.rankSourceId !== 0 && r.published && r.rank > 0)
      .map((r: any) => r.rank)
      .sort((a: number, b: number) => a - b);

    // Presence, not truthiness. A waiverProcessDate of 0 means "not on
    // waivers", not "clears at the epoch".
    const clears =
      Object.prototype.hasOwnProperty.call(e, "waiverProcessDate") &&
      e.waiverProcessDate > 0
        ? new Date(e.waiverProcessDate).toISOString()
        : null;

    return {
      id: p.id,
      name: p.fullName,
      pos: POS[p.defaultPositionId] || String(p.defaultPositionId),
      proTeamId: p.proTeamId,
      injury: p.injuryStatus,
      proj: weekProj(p, period),
      owned: own.percentOwned,
      ownedChange: own.percentChange,
      started: own.percentStarted,
      status: e.status,
      clearsAt: clears,
      rank: ranks.length ? ranks[Math.floor(ranks.length / 2)] : null,
    };
  });

  const available = rows.filter(
    (w: any) => w.status === "FREEAGENT" || w.status === "WAIVERS"
  );

  // The founding signal: low absolute ownership plus a fast rise. Same gate
  // as WirePlayer.isMoneySignal in the app, so the two cannot drift.
  /**
   * Three bands, merged — deliberately not one sort key.
   *
   * Ownership velocity catches a player the wider world is reacting to before
   * this league notices, and it is the founding signal of the project. But it
   * is blind to a good player who is simply available because someone made a
   * mistake: a just-dropped star has a flat or negative delta and sorts to the
   * bottom. Brian Thomas Jr was dropped in Chem and fell outside the top 120
   * on a pure-velocity sort — the single most valuable thing on the wire,
   * cut by the ranking meant to surface it.
   *
   * Those are different signals. Collapsing them into one key loses the
   * second, so each band takes its own slice and they merge by first-seen.
   */
  const money = (w: any) => (w.owned || 0) < 25 && (w.ownedChange || 0) >= 1.5;
  const byOwned = (a: any, b: any) => (b.owned || 0) - (a.owned || 0);
  const byProj = (a: any, b: any) => (b.proj || 0) - (a.proj || 0);
  const byDelta = (a: any, b: any) => (b.ownedChange || 0) - (a.ownedChange || 0);

  const tag = (rows: any[], why: string) => rows.map((r) => ({ ...r, why }));

  const bands = [
    // Everyone clearing the gate, however many that is.
    tag(available.filter(money).sort(byDelta), "MONEY"),
    // Widely rostered elsewhere but free here: someone blundered.
    tag(available.slice().sort(byOwned).slice(0, 45), "OWNED"),
    // This week's startable bodies, whatever their ownership.
    tag(available.slice().sort(byProj).slice(0, 40), "PROJ"),
    // Rising but short of the money gate.
    tag(available.slice().sort(byDelta).slice(0, 35), "RISER"),
  ];

  const seen = new Set<number>();
  const merged: any[] = [];
  for (const band of bands) {
    for (const r of band) {
      if (!seen.has(r.id)) { seen.add(r.id); merged.push(r); }
    }
  }
  return merged.slice(0, WIRE_KEEP);
}

/**
 * Byes, opponents and kickoffs for every NFL team.
 *
 * Season level: NO /segments path. Sending one returns 404.
 *
 * This retires the hardcoded proTeamId table. `abbrev` covers all 33 entries,
 * including id 0, which is ESPN's free-agency placeholder and not a team —
 * hence the guard.
 */
async function fetchProTeams(season: number, period: number) {
  const d = await espnGet(`/apis/v3/games/ffl/seasons/${season}?view=proTeamSchedules_wl`);
  const out: Record<string, any> = {};
  for (const t of d.settings?.proTeams || []) {
    if (!t.id) continue;
    const games = t.proGamesByScoringPeriod?.[String(period)] || [];
    const g = games[0];
    out[t.id] = {
      abbrev: t.abbrev,
      bye: t.byeWeek,
      // An empty games array for the week IS the bye, and survives a
      // reschedule in a way the static byeWeek field does not.
      onBye: games.length === 0,
      opp: g ? (g.homeProTeamId === t.id ? g.awayProTeamId : g.homeProTeamId) : null,
      home: g ? g.homeProTeamId === t.id : null,
      // startTimeTBD is an explicit flag. Do not infer it from date === 0.
      kickoff: g && !g.startTimeTBD && g.date ? new Date(g.date).toISOString() : null,
      tbd: g ? !!g.startTimeTBD : null,
    };
  }
  return out;
}

const ACTIVITY_FILTER = JSON.stringify({
  topics: {
    filterType: { value: ["ACTIVITY_TRANSACTIONS"] },
    limit: 25,
    limitPerMessageSet: { value: 25 },
    offset: 0,
    sortMessageDate: { sortPriority: 1, sortAsc: false },
  },
});

/**
 * Verified against live roster state on 12 Sep 2026, not inherited from the
 * Ruby CLI — whose rule that `to === -1` means DROP does not appear anywhere
 * in this season's data. Not one -1 in fifty messages.
 */
const MSG_KIND: Record<number, string> = {
  178: "ADD",
  179: "DROP",
  180: "WAIVER_ADD",
  181: "WAIVER_DROP",
  188: "LINEUP",
};

/**
 * The league transaction log.
 *
 * Lives at a DIFFERENT path — {league}/communication/ — and sending its filter
 * to the league endpoint returns 400.
 *
 * PRIVACY: every message carries `author`, which is another manager's raw ESPN
 * SWID, and scrub() only knows about ours. Six distinct SWIDs appeared in a
 * single 25-topic pull. This repo is public and git history is forever, so the
 * author field is dropped here and must never be added back. Team ids say
 * everything the analysis needs.
 *
 * Note 188 is most of the traffic and is a LINEUP move, not a transaction.
 * Its `to` and `from` are lineupSlotIds and `for` is the team; on a real
 * transaction it is `to` that carries the team. Treating every message as an
 * add would report the owner's own bench shuffles as league activity.
 */
function slimActivity(d: any): any[] {
  const out: any[] = [];
  for (const t of d.topics || []) {
    for (const m of t.messages || []) {
      const kind = MSG_KIND[m.messageTypeId];
      if (!kind) continue; // unknown id: skip rather than guess at it
      const row: any = {
        at: new Date(m.date).toISOString(),
        kind,
        playerId: m.targetId,
        teamId: kind === "LINEUP" ? m.for : m.to,
      };
      if (kind === "LINEUP") {
        row.fromSlot = m.from;
        row.toSlot = m.to;
      }
      out.push(row);
    }
  }
  return out.slice(0, 60);
}

async function espnGet(path: string, filter?: string): Promise<any> {
  const headers: Record<string, string> = {
    Cookie: `espn_s2=${ESPN_S2.value()}; SWID=${ESPN_SWID.value()}`,
    Accept: "application/json",
  };
  if (filter) headers["x-fantasy-filter"] = filter;
  const r = await fetch(`https://lm-api-reads.fantasy.espn.com${path}`, { headers });
  if (!r.ok) throw new Error(`ESPN ${r.status} on ${path}`);
  return r.json();
}

/** Every league this account is in, for the current season. */
async function myLeagues(season: number): Promise<number[]> {
  const d = await espnGet(
    `/apis/v3/games/ffl/seasons/${season}?view=chui_default`
  ).catch(() => null);
  if (d?.leagues) return d.leagues.map((l: any) => l.id);
  // Fall back to whatever the app has uploaded, so a discovery failure does
  // not silently publish nothing.
  const snap = await db.collection("briefs").get();
  return [...new Set(snap.docs.map((x) => Number(x.data().leagueId)))];
}

async function commit(path: string, content: string) {
  const api = `https://api.github.com/repos/${GH_REPO}/contents/${path}`;
  const head: Record<string, string> = {
    Authorization: `Bearer ${GH_TOKEN.value()}`,
    Accept: "application/vnd.github+json",
    "User-Agent": "fantasy-brief-publisher",
  };
  const existing = await fetch(api, { headers: head })
    .then((r) => (r.ok ? r.json() : null))
    .catch(() => null);

  await fetch(api, {
    method: "PUT",
    headers: { ...head, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: `publish ${path}`,
      content: Buffer.from(content).toString("base64"),
      branch: "main",
      ...(existing?.sha ? { sha: existing.sha } : {}),
    }),
  });
}

export const publish = onSchedule(
  {
    schedule: "every 15 minutes",
    secrets: [ESPN_S2, ESPN_SWID, GH_TOKEN],
    maxInstances: 1,
    timeoutSeconds: 120,
  },
  async () => {
    const season = new Date().getFullYear();
    const leagues = await myLeagues(season);

    for (const id of leagues) {
      try {
        const q = VIEWS.map((v) => `view=${v}`).join("&");
        const base = `/apis/v3/games/ffl/seasons/${season}/segments/0/leagues/${id}`;
        const raw = await espnGet(`${base}?${q}`);
        const clean = JSON.parse(scrub(JSON.stringify(raw), ESPN_SWID.value()));
        const out: any = slim(clean);

        // Best effort, in its own try. A wire failure must not cost us the
        // rosters, which are what the app actually renders.
        try {
          const pool = await espnGet(`${base}?view=kona_player_info`, WIRE_FILTER);
          out.wire = slimWire(pool, out.scoringPeriod);
        } catch (we) {
          out.wire = [];
          out.wireError = String(we).slice(0, 200);
        }

        try {
          out.proTeams = await fetchProTeams(season, out.scoringPeriod);
        } catch (pe) {
          out.proTeamsError = String(pe).slice(0, 200);
        }

        try {
          const act = await espnGet(
            `${base}/communication/?view=kona_league_communication`,
            ACTIVITY_FILTER
          );
          out.activity = slimActivity(act);
        } catch (ae) {
          out.activity = [];
          out.activityError = String(ae).slice(0, 200);
        }

        await commit(`league-${id}.json`, JSON.stringify(out));
      } catch (e) {
        // One league failing must not stop the others.
        await commit(
          `league-${id}-error.json`,
          JSON.stringify({ error: String(e).slice(0, 300), at: new Date().toISOString() })
        );
      }
    }
  }
);


/** Weekly projection: statSourceId 1, statSplitTypeId 1, this scoring period. */
function weekProj(p: any, period: number): number | null {
  const row = (p.stats || []).find(
    (r: any) =>
      r.statSourceId === 1 && r.statSplitTypeId === 1 &&
      r.scoringPeriodId === period
  );
  return row ? Math.round(row.appliedTotal * 10) / 10 : null;
}

/** Actual points scored this period, if any. */
function weekActual(p: any, period: number): number | null {
  const row = (p.stats || []).find(
    (r: any) =>
      r.statSourceId === 0 && r.statSplitTypeId === 1 &&
      r.scoringPeriodId === period
  );
  return row ? Math.round(row.appliedTotal * 10) / 10 : null;
}

/**
 * Strips the payload to what an analysis needs.
 *
 * Drops per-season stat dictionaries, prose outlooks, draft ranks and the
 * ownership/auction noise — about 95% of the bytes. What survives is who is
 * on which roster, in which slot, with this week's projection, actual,
 * injury status, ownership and the analyst ranks.
 */
function slim(d: any): any {
  const period = d.scoringPeriodId;
  const POS: Record<number, string> = {
    1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "DST",
  };

  const teams = (d.teams || []).map((t: any) => ({
    id: t.id,
    name: t.name,
    isMine: t.isMine,
    waiverRank: t.waiverRank,
    record: t.record?.overall,
    roster: (t.roster?.entries || []).map((e: any) => {
      const p = e.playerPoolEntry?.player || {};
      const ranks = (p.rankings?.[String(period)] || [])
        .filter((r: any) =>
          r.rankType === "PPR" && r.rankSourceId !== 0 && r.published && r.rank > 0)
        .map((r: any) => r.rank)
        .sort((a: number, b: number) => a - b);
      return {
        id: p.id,
        name: p.fullName,
        pos: POS[p.defaultPositionId] || String(p.defaultPositionId),
        slotId: e.lineupSlotId,
        proTeamId: p.proTeamId,
        injury: p.injuryStatus,
        proj: weekProj(p, period),
        actual: weekActual(p, period),
        owned: p.ownership?.percentOwned,
        ownedChange: p.ownership?.percentChange,
        // Median of the analyst ranks, plus the spread when they disagree.
        rank: ranks.length ? ranks[Math.floor(ranks.length / 2)] : null,
        rankLow: ranks.length ? ranks[0] : null,
        rankHigh: ranks.length ? ranks[ranks.length - 1] : null,
      };
    }),
  }));

  return {
    leagueId: d.id,
    season: d.seasonId,
    scoringPeriod: period,
    matchupPeriod: d.status?.currentMatchupPeriod,
    myTeamId: d.myTeamId,
    settings: {
      name: d.settings?.name,
      size: d.settings?.size,
      lineup: d.settings?.rosterSettings?.lineupSlotCounts,
      scoring: d.settings?.scoringSettings?.scoringItems?.find(
        (i: any) => i.statId === 53)?.points,
      waiver: {
        process: d.settings?.acquisitionSettings?.waiverProcessDays,
        orderReset: d.settings?.acquisitionSettings?.waiverOrderReset,
        hours: d.settings?.acquisitionSettings?.waiverHours,
      },
    },
    schedule: (d.schedule || [])
      .filter((m: any) => m.matchupPeriodId === d.status?.currentMatchupPeriod)
      .map((m: any) => ({
        home: m.home?.teamId, away: m.away?.teamId,
        homeLive: m.home?.totalPointsLive, awayLive: m.away?.totalPointsLive,
        homeProjLive: m.home?.totalProjectedPointsLive,
        awayProjLive: m.away?.totalProjectedPointsLive,
        homeWinProb: m.home?.winProbability,
      })),
    teams,
    publishedAt: new Date().toISOString(),
  };
}
