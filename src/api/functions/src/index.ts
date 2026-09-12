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
        const raw = await espnGet(
          `/apis/v3/games/ffl/seasons/${season}/segments/0/leagues/${id}?${q}`
        );
        const clean = JSON.parse(scrub(JSON.stringify(raw), ESPN_SWID.value()));
        await commit(`league-${id}.json`, JSON.stringify(slim(clean)));
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
