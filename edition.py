#!/usr/bin/env python3
"""Build the Insights edition payload — edition-<leagueId>.json.

The Insights tab renders entirely from this file; the app computes nothing.
So every number the screen shows is computed here, from ESPN's data, and every
sentence comes from the brief run's research (the text layer). Numbers from
data, words from research — never the other way round.

Inputs
  league-<L>.json        ESPN league file (published by `publish`)
  week-<L>-<n>.json      week archives, one per finished week
  text-<L>.json          the research text layer written by the brief run
Output
  edition-<L>.json       the payload described in EDITION-SCHEMA.md

Nothing league-specific is hardcoded: slot rules, flex eligibility, season
length, team names and waiver days all come from the league file.

Usage:  python3 edition.py <leagueId> [--dir PATH] [--out PATH] [--dry-run]
"""
import argparse
import datetime as dt
import json
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lineup_check import SLOTS, SLOT_NAME, best_lineup, slot_list  # noqa: E402

SCHEMA = 1
CDMX = dt.timezone(dt.timedelta(hours=-6))
POSN = {1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "DST"}
POS_ORDER = {"QB": 0, "RB": 1, "WR": 2, "TE": 3, "FLEX": 4, "D/ST": 5, "K": 6}
IN_DOUBT = {"QUESTIONABLE", "DOUBTFUL", "DAY_TO_DAY"}
UNAVAILABLE = {"OUT", "INJURY_RESERVE", "SUSPENSION"}
COIN_FLIP = 2.0
SEASON_SCALE = 1.06   # weekly projections vs season/17, measured on Chem's roster
TAGS = [(1.20, "ELITE"), (1.08, "FAVORABLE"), (0.92, "NEUTRAL"), (0.80, "TOUGH"), (0.0, "AVOID")]
DEFAULT_SLOTS = 14


def load(path):
    with open(path) as f:
        return json.load(f)


def cdmx(iso):
    return dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(CDMX)


def slot_label(s):
    return {16: "D/ST", 17: "K", 20: "BE", 21: "IR"}.get(s, SLOT_NAME.get(s, "?"))


def pos_label(pos):
    return "D/ST" if pos == "DST" else pos


SUFFIXES = {"Jr.", "Jr", "Sr.", "Sr", "II", "III", "IV", "V"}


def surname(name):
    """'James Cook III' -> 'Cook', not 'III'."""
    if "D/ST" in name:
        return name
    parts = [w for w in name.split() if w not in SUFFIXES]
    return parts[-1] if parts else name


class Edition:
    def __init__(self, lid, base, text):
        self.lid = str(lid)
        self.lg = load(os.path.join(base, f"league-{lid}.json"))
        self.text = text
        self.week = self.lg["scoringPeriod"]
        self.now = dt.datetime.now(dt.timezone.utc)
        self.teams = self.lg["proTeams"]
        self.mine = next(t for t in self.lg["teams"] if t["isMine"])
        self.archives = {}
        for w in range(1, self.week):
            p = os.path.join(base, f"week-{lid}-{w}.json")
            if os.path.exists(p):
                self.archives[w] = load(p)
        self.pools = {w: {x["id"]: x for x in a.get("pool", [])} for w, a in self.archives.items()}
        # weekly projections for anyone rostered anywhere that week
        self.arch_rows = {w: {x["id"]: x for t in a["teams"] for x in t["roster"]} for w, a in self.archives.items()}
        self.wire = {w["id"]: w for w in self.lg.get("wire", [])}
        self.roster = self.mine["roster"]
        self.best = best_lineup(self.lg, self.roster, self.now)
        self.best_ids = {p["id"] for _, p in self.best}
        # the ESPN-reported regular season, when publish supplies it
        rs = self.lg["settings"].get("regularSeasonWeeks")
        self.slots, self.slots_source = (rs, "espn") if rs else (max(DEFAULT_SLOTS, self.week), "default")
        self.starter_avg = self._starter_averages()

    # ---- helpers ---------------------------------------------------------
    def team(self, p):
        return self.teams.get(str(p.get("proTeamId"))) or {}

    def team_name(self, t):
        return t.get("location") or t.get("name") or t.get("abbrev", "?")

    def kickoff(self, p):
        k = self.team(p).get("kickoff")
        return cdmx(k) if k else None

    def status(self, p):
        return (p.get("injury") or "ACTIVE").upper()

    def links(self):
        L, T, S, W = self.lid, self.lg["myTeamId"], self.lg["season"], self.week
        return {
            "lineup": f"https://fantasy.espn.com/football/team?leagueId={L}&teamId={T}&seasonId={S}",
            "matchup": (f"https://fantasy.espn.com/football/boxscore?leagueId={L}&matchupPeriodId={W}"
                        f"&scoringPeriodId={W}&seasonId={S}&teamId={T}"),
            "freeAgents": f"https://fantasy.espn.com/football/players/add?leagueId={L}",
            "injuries": "https://www.espn.com/nfl/injuries",
        }

    @staticmethod
    def news(pid):
        return f"https://www.espn.com/nfl/player/_/id/{pid}" if pid and pid > 0 else None

    def touches(self, line, pos):
        if pos == "QB":
            return (line.get("passAtt") or 0) + (line.get("carries") or 0)
        return (line.get("targets") or 0) + (line.get("carries") or 0)

    def _starter_averages(self):
        """Per week, per position: what the average STARTER in this league did —
        points, touches, points per touch and share. Every team's lineup is in
        the archive, so this is the league's own bar, not the NFL's: an average
        over all players with touches includes backups and flatters everyone."""
        out = {}
        for w, a in self.archives.items():
            pool = self.pools.get(w, {})
            groups = {}
            for t in a["teams"]:
                for r in t["roster"]:
                    if r["slotId"] in (20, 21):
                        continue
                    pos = POSN.get(r["pos"], r["pos"]) if isinstance(r["pos"], int) else r["pos"]
                    groups.setdefault(pos, []).append(r)
            for pos, rows in groups.items():
                if pos in ("K", "DST"):
                    pts = [r["actual"] for r in rows if r.get("actual") is not None]
                    out[(w, pos)] = {"pts": round(st.mean(pts), 1) if pts else None}
                    continue
                lines = [pool[r["id"]] for r in rows if r["id"] in pool and self.touches(pool[r["id"]], pos)]
                if not lines:
                    continue
                touch = [self.touches(l, pos) for l in lines]
                shares = [s for s in (self.share(l["id"], l.get("proTeamId"), pos, w) for l in lines) if s is not None]
                out[(w, pos)] = {
                    "pts": round(st.mean(l["pts"] for l in lines), 1),
                    "touches": round(st.mean(touch), 1),
                    "eff": round(st.mean(l["pts"] / t for l, t in zip(lines, touch)), 2),
                    "share": round(st.mean(shares), 3) if shares and pos != "QB" else None,
                }
        return out

    def _position_efficiency(self):
        """Per week, per position: average points per touch among players with 5+ touches."""
        out = {}
        for w, pool in self.pools.items():
            for pos in ("QB", "RB", "WR", "TE"):
                xs = [l["pts"] / self.touches(l, pos) for l in pool.values()
                      if POSN.get(l.get("pos")) == pos and self.touches(l, pos) >= 5]
                out[(w, pos)] = round(st.mean(xs), 2) if xs else None
        return out

    def share(self, pid, team, pos, w):
        key = "carries" if pos == "RB" else "targets"
        grp = {"RB"} if pos == "RB" else {"RB", "WR", "TE"}
        pool = self.pools.get(w, {})
        tot = sum((x.get(key) or 0) for x in pool.values()
                  if x.get("proTeamId") == team and POSN.get(x.get("pos")) in grp)
        line = pool.get(pid)
        if not line or not self.touches(line, pos):
            return None
        return round((line.get(key) or 0) / tot, 3) if tot else None

    def weeks(self, p):
        """One entry per finished week: an object, or null for an absence.
        Future weeks are simply missing — the app draws them as empty slots."""
        pos, out = p["pos"], []
        for w in range(1, self.week):
            line = self.pools.get(w, {}).get(p["id"])
            row = self.arch_rows.get(w, {}).get(p["id"])
            proj = (row or {}).get("proj")
            if pos in ("K", "DST"):
                pts = (row or {}).get("actual")
                out.append(None if pts is None else {"week": w, "pts": pts, "proj": proj,
                                                     "posAvgPts": (self.starter_avg.get((w, pos)) or {}).get("pts")})
                continue
            if not line or not self.touches(line, pos):
                out.append(None)
                continue
            t = self.touches(line, pos)
            avg = self.starter_avg.get((w, pos)) or {}
            out.append({"week": w, "pts": line["pts"], "proj": proj, "touches": t,
                        "eff": round(line["pts"] / t, 2) if t else None,
                        "posAvgPts": avg.get("pts"), "posAvgTouches": avg.get("touches"),
                        "posAvgEff": avg.get("eff"), "posAvgShare": avg.get("share"),
                        "share": self.share(p["id"], p.get("proTeamId"), pos, w) if pos != "QB" else None})
        return out

    @staticmethod
    def summary(vals):
        xs = [v for v in vals if v is not None]
        return {"last": xs[-1] if xs else None, "avg": round(st.mean(xs), 2) if xs else None}

    def normal(self, p, weeks):
        prev = [w["proj"] for w in weeks if w and w.get("proj")]
        if prev:
            return sum(prev) / len(prev), False
        return (p.get("seasonProj") or 0) / 17 * SEASON_SCALE, True

    def tag(self, pid, ratio):
        idx = next(i for i, (t, _) in enumerate(TAGS) if ratio >= t)
        notch = int(self.text.get("unpriced", {}).get(str(pid), 0))
        return TAGS[min(max(idx - notch, 0), len(TAGS) - 1)][1]

    def eligible(self, slot_id, pos):
        return pos in SLOTS.get(slot_id, set())

    # ---- players -----------------------------------------------------------
    def starters(self):
        """The lineup as set, in lineup order: slot id and player."""
        rows = [(p["slotId"], p) for p in self.roster if p["slotId"] not in (20, 21)]
        return sorted(rows, key=lambda sp: POS_ORDER.get(slot_label(sp[0]), 9))

    def challengers(self, p, starting, slot_id):
        if starting:
            bench = [b for b in self.roster if b["slotId"] == 20 and self.eligible(slot_id, b["pos"])]
            if bench:
                b = max(bench, key=lambda b: b.get("proj") or 0)
                return "CLOSEST CHALLENGER", [self._gap_row("BENCH", b, p)]
            free = [w for w in self.wire.values() if w.get("pos") == p["pos"] and w.get("proj")]
            if free:
                b = max(free, key=lambda w: w["proj"])
                return "CLOSEST CHALLENGER", [self._gap_row("FREE AGENT", b, p)]
            return None, []
        rows = [self._gap_row(slot_label(s), q, p, mine_first=True) for s, q in self.starters()
                if self.eligible(s, p["pos"])]
        rows.sort(key=lambda r: -r["gap"])
        return "COULD START OVER", rows[:3]

    def _gap_row(self, where, other, me, mine_first=False):
        gap = round((me.get("proj") or 0) - (other.get("proj") or 0), 1)
        label = "coin flip" if abs(gap) < COIN_FLIP else ("on the line" if abs(gap) == COIN_FLIP else None)
        return {"where": where, "playerId": other["id"], "name": other["name"],
                "proj": other.get("proj"), "gap": gap, "gapLabel": label}

    def verdict(self, p, t, starting, fa=False):
        kind = (t.get("verdict") or ("START" if starting else "BENCH")).upper()
        if fa:
            kind = kind if kind in ("ADD", "WATCH") else "WATCH"
        if kind == "CONDITIONAL":
            k = self.kickoff(p)
            check = (k - dt.timedelta(minutes=90)).strftime("%-H:%M") if k else "kickoff"
            return {"kind": "IF_ACTIVE", "text": f"START IF ACTIVE — ELSE {(t.get('fallback') or '?').upper()} AT {check}"}
        if kind == "ADD":
            return {"kind": "ADD", "text": f"ADD — DROP {(t.get('drop') or '?').split(' —')[0].upper()}"}
        if kind == "WATCH":
            return {"kind": "WATCH", "text": "WATCH — NOT ADDING THIS WEEK"}
        return {"kind": kind, "text": kind}

    def player(self, p, slot_id, starting, fa=False):
        pid = str(p["id"])
        t = self.text.get("players", {}).get(pid)
        if not t:
            raise SystemExit(f"text layer has no entry for {p['name']} ({pid}) — every player needs research")
        weeks = self.weeks(p)
        normal, est = self.normal(p, weeks)
        ratio = (p.get("proj") or 0) / normal if normal else 1.0
        team = self.team(p)
        opp = self.teams.get(str(team.get("opp"))) or {}
        k = self.kickoff(p)
        where = "FA" if fa else ("BE" if not starting else slot_label(slot_id))
        slot_line = (f"{where} · {pos_label(p['pos'])} · {team.get('abbrev')}" if where in ("FA", "BE", "FLEX")
                     else f"{where} · {team.get('abbrev')}")
        title, rows = self.challengers(p, starting, slot_id)
        prev = next((w.get("proj") for w in reversed(weeks) if w and w.get("proj")), None)
        table = [["AVAILABILITY", t["availability"], "plain"]]
        if p["pos"] not in ("K", "DST"):
            table.append(["ROLE", t.get("role"), "plain"])
            if p["pos"] != "QB":
                table += [["COMPETITION", t.get("competition"), "plain"], ["PASSER", t.get("passer"), "plain"]]
        if t.get("vsProjection"):
            table.append(["VS PROJECTION", f"{t['vsProjection']['tag']} {t['vsProjection']['text']}", "priced"])
        table.append(["CHECKED", t["checked"], "italic"])
        if fa:
            table += [["THE DROP", t.get("drop"), "plain"], ["ADD IF", t.get("addif"), "plain"]]
        table = [{"label": a, "value": b, "style": c} for a, b, c in table if b]
        pos_ = p["pos"]
        series = {"points": self.summary([w and w.get("pts") for w in weeks])}
        if pos_ not in ("K", "DST"):
            series["touches"] = self.summary([w and w.get("touches") for w in weeks])
            series["eff"] = self.summary([w and w.get("eff") for w in weeks])
            if pos_ != "QB":
                series["share"] = self.summary([w and w.get("share") for w in weeks])
        line = {
            "playerId": p["id"], "name": p["name"], "pos": pos_, "team": team.get("abbrev"),
            "teamName": self.team_name(team), "logo": (team.get("abbrev") or "").lower(),
            "slotLine": slot_line, "starting": starting, "questionable": self.status(p) in IN_DOUBT,
            "proj": p.get("proj"), "normal": round(normal, 1), "normalEstimated": est,
            "pct": round((ratio - 1) * 100), "tag": self.tag(pid, ratio), "reason": t["reason"],
            "sheet": {
                "slotLine": ("FREE AGENT · " + pos_label(pos_)) if fa else
                            (("STARTING · " + slot_label(slot_id)) if starting else "BENCH · " + pos_label(pos_)),
                "opponent": f"{team.get('abbrev')} {'vs' if team.get('home') else '@'} {opp.get('abbrev', '?')}",
                "kickoff": k.strftime("%a %H:%M") if k else None,
                "deck": t["deck"], "verdict": self.verdict(p, t, starting, fa),
                "flags": t.get("flags", []),
                "figures": {"experts": self._experts(p), "projMove": f"{prev:.1f} → {p['proj']:.1f}" if prev else None,
                            "matchup": t.get("matchupGrade")},
                "range": ({"rank": p["rank"], "best": p["rankLow"], "worst": p["rankHigh"]}
                          if p.get("rank") and p.get("rankLow") and p.get("rankHigh") else None),
                "weeks": weeks, "current": {"week": self.week, "proj": p.get("proj")},
                "shareKey": None if pos_ in ("QB", "K", "DST") else ("carries" if pos_ == "RB" else "targets"),
                "series": series, "table": table,
                "challengers": {"title": title, "self": p.get("proj"), "rows": rows} if rows else None,
                "logic": t["logic"],
            },
        }
        if fa:
            w = self.wire.get(p["id"], p)
            line["fa"] = {"owned": w.get("owned"), "chg": w.get("ownedChange"),
                          "chip": line["sheet"]["verdict"]["kind"]}
        return line

    def _experts(self, p):
        if not p.get("rank"):
            return None
        s = f"{pos_label(p['pos'])}{p['rank']}"
        if p.get("rankLow") and p.get("rankHigh") and p["rankLow"] != p["rankHigh"]:
            s += f" ({p['rankLow']}–{p['rankHigh']})"
        return s

    # ---- sections ------------------------------------------------------------
    def front(self):
        lead = dict(self.text["lead"])
        by_id = {p["id"]: p for p in self.roster}
        lead["players"] = [{"playerId": x["playerId"], "name": by_id[x["playerId"]]["name"],
                            "caption": f"{slot_label(by_id[x['playerId']]['slotId'])} · "
                                       f"{self.team_name(self.team(by_id[x['playerId']]))}. {x['caption']}",
                            "logo": (self.team(by_id[x["playerId"]]).get("abbrev") or "").lower()}
                           for x in lead.get("players", [])]
        m = next(x for x in self.lg["schedule"] if self.lg["myTeamId"] in (x["home"], x["away"]))
        home = m["home"] == self.lg["myTeamId"]
        opp = next(t for t in self.lg["teams"] if t["id"] == (m["away"] if home else m["home"]))
        theirs = sorted([(x["slotId"], x) for x in opp["roster"] if x["slotId"] not in (20, 21)],
                        key=lambda sp: POS_ORDER.get(slot_label(sp[0]), 9))
        slots = []
        def played(p):
            k = self.kickoff(p)
            return bool(k and k <= self.now and p.get("actual") is not None)
        for (s, a), (_, b) in zip(self.starters(), theirs):
            ma, tb = played(a), played(b)
            slots.append({"slot": slot_label(s),
                          "mine": {"id": a["id"], "name": surname(a["name"]), "pos": a["pos"], "proj": a.get("proj"),
                                   "value": a["actual"] if ma else a.get("proj"), "final": ma},
                          "theirs": {"id": b["id"], "name": surname(b["name"]), "value": b["actual"] if tb else b.get("proj"),
                                     "final": tb}})
        groups = {}
        for p in self.roster:
            k = self.kickoff(p)
            if k:
                groups.setdefault(k.isoformat(), [k, [], []])
                groups[k.isoformat()][1 if p["slotId"] not in (20, 21) else 2].append(p)
        listings = [{"time": k.strftime("%a %H:%M"),
                     "starters": [surname(p["name"]) for p in sorted(s, key=lambda p: POS_ORDER.get(slot_label(p["slotId"]), 9))],
                     "bench": [surname(p["name"]) for p in b]}
                    for _, (k, s, b) in sorted(groups.items())]
        return {"lead": lead, "matchup": {
            "me": {"name": self.mine["name"], "live": m["homeLive"] if home else m["awayLive"],
                   "proj": round(m["homeProjLive"] if home else m["awayProjLive"], 1)},
            "them": {"name": opp["name"], "live": m["awayLive"] if home else m["homeLive"],
                     "proj": round(m["awayProjLive"] if home else m["homeProjLive"], 1)},
            "winProb": round(m["homeWinProb"] if home else 1 - m["homeWinProb"], 3),
            "slots": slots, "listings": listings}}

    def sunday_of(self, week):
        """The Sunday of a given week, from this week's Sunday kickoffs."""
        ks = sorted(cdmx(t["kickoff"]) for t in self.teams.values() if t.get("kickoff"))
        sunday = next((k for k in ks if k.weekday() == 6), ks[-1]).date()
        return sunday + dt.timedelta(days=7 * (week - self.week))

    def by_name(self, name):
        """A roster or wire player from a name the research wrote ('Bhayshul Tuten — ...')."""
        n = (name or "").split(" —")[0].split(",")[0].strip()
        for p in list(self.roster) + list(self.wire.values()):
            if p["name"] == n:
                return p
        return None

    def do_first(self, roster_lines, wire_lines):
        cards = []
        lines = {l["playerId"]: l for l in roster_lines + wire_lines}
        for c in self.text["doFirst"]["cards"]:
            card = {"id": c["id"], "headline": c["headline"], "reason": c["reason"],
                    "rows": [{"label": a, "value": b} for a, b in c.get("rows", [])],
                    "playerId": c.get("playerId")}
            if c["kind"] == "contingency":
                p = next(x for x in self.roster if x["id"] == c["playerId"])
                check = self.kickoff(p) - dt.timedelta(minutes=90)
                fb = self.by_name(self.text["players"][str(p["id"])].get("fallback"))
                card.update(urgency="today", due=check.isoformat(),
                            tag=f"TODAY · {check.strftime('%H:%M')} · INACTIVES", short=f"TODAY {check.strftime('%H:%M')}",
                            primary=({"label": f"START {surname(fb['name']).upper()}",
                                      "action": {"type": "swap", "start": fb["id"], "bench": p["id"]}} if fb else
                                     {"label": "SET LINEUP", "action": {"type": "tab", "tab": "roster"}}),
                            secondary={"label": f"{surname(p['name']).upper()}'S CARD",
                                       "action": {"type": "sheet", "playerId": p["id"]}})
            elif c["kind"] in ("conditionalClaim", "claim"):
                d = dt.date.fromisoformat(c["deadline"])
                card.update(urgency="week", due=d.isoformat(),
                            tag=f"THIS WEEK · {d.strftime('%a %-d %b').upper()} · WAIVERS", short=d.strftime("%a %-d %b").upper(),
                            primary={"label": f"ADD {surname(lines[c['playerId']]['name']).upper()}",
                                     "action": {"type": "add", "playerId": c["playerId"],
                                                "drop": (self.by_name(self.text["players"][str(c["playerId"])].get("drop")) or {}).get("id")}},
                            secondary={"label": f"{surname(lines[c['playerId']]['name']).upper()}'S CARD",
                                       "action": {"type": "sheet", "playerId": c["playerId"]}})
            elif c["kind"] == "byePlan":
                d = self.sunday_of(c["week"]) - dt.timedelta(days=5)
                card.update(urgency="ahead", due=d.isoformat(),
                            tag=f"PLAN AHEAD · BY {d.strftime('%a %-d %b').upper()}", short=f"BY {d.strftime('%a %-d %b').upper()}",
                            primary={"label": "BROWSE FREE AGENTS", "action": {"type": "tab", "tab": "wire"}},
                            secondary={"label": "BYES AHEAD", "action": {"type": "byes"}})
                card["rows"] = [{"label": "COVER", "value": self.bye_plan()["caption"]}] + card["rows"]
            cards.append(card)
        cards.sort(key=lambda c: c["due"])
        t = self.text["doFirst"]
        return {"headline": t["headline"], "deck": t["deck"], "cards": cards, "note": t["note"],
                "empty": None if cards else (t.get("empty") or "Nothing to do this week. The lineup is the projection lineup.")}

    def bye_plan(self):
        weeks = list(range(self.week + 1, self.slots + 1))
        counts = {w: {"week": w, "starters": 0, "bench": 0} for w in weeks}
        starters = [p for _, p in self.starters()]
        for p in self.roster:
            b = self.team(p).get("bye")
            if b in counts:
                counts[b]["starters" if p["slotId"] not in (20, 21) else "bench"] += 1
        worst = max(counts.values(), key=lambda c: c["starters"]) if counts else None
        caption = None
        if worst and worst["starters"] >= 3:
            out = [p for p in starters if self.team(p).get("bye") == worst["week"]]
            bench = [b for b in self.roster if b["slotId"] == 20 and self.team(b).get("bye") != worst["week"]]
            used, parts = set(), []
            for p in out:
                s = p["slotId"]
                cover = [b for b in bench if self.eligible(s, b["pos"]) and b["id"] not in used]
                if cover:
                    b = max(cover, key=lambda b: b.get("proj") or 0)
                    used.add(b["id"])
                    parts.append(f"{surname(p['name'])} → {surname(b['name'])}")
                else:
                    parts.append(f"{surname(p['name'])} → stream")
            caption = "; ".join(parts)
        return {"weeks": list(counts.values()), "worstWeek": worst["week"] if worst and worst["starters"] >= 3 else None,
                "caption": caption}

    def market(self):
        out = {}
        for pos, key in (("K", "kicker"), ("DST", "defence")):
            mine = [p for p in self.roster if p["pos"] == pos]
            free = sorted([w for w in self.wire.values() if w.get("pos") == pos and w.get("proj")],
                          key=lambda w: -w["proj"])[:3]
            if not mine:
                continue
            m = max(mine, key=lambda p: p.get("proj") or 0)
            best_free = free[0]["proj"] if free else 0
            out[key] = {"action": "HOLD" if (m.get("proj") or 0) >= best_free else "SWAP",
                        "rows": [{"name": m["name"], "proj": m.get("proj"), "mine": True}] +
                                [{"name": w["name"], "proj": w["proj"], "mine": False} for w in free]}
        return out

    def last_week(self):
        w = self.week - 1
        a = self.archives.get(w)
        if not a:
            return None
        t = next((t for t in a["teams"] if str(t.get("id")) == str(self.lg["myTeamId"])), None)
        if not t:
            return None
        pos = lambda p: POSN.get(p["pos"], p["pos"]) if isinstance(p["pos"], int) else p["pos"]
        started = sum(p.get("actual") or 0 for p in t["roster"] if p["slotId"] not in (20, 21))
        used, best = set(), 0.0
        for s in slot_list(self.lg):
            c = [p for p in t["roster"] if p["id"] not in used and pos(p) in SLOTS[s]]
            if c:
                x = max(c, key=lambda p: p.get("actual") or 0)
                used.add(x["id"])
                best += x.get("actual") or 0
        return (f"Week {w} left {best - started:.1f} points on the bench. The lineup scored {started:.1f} "
                f"against {best:.1f} available — from the archive, not articles.")

    def build(self, dry_run):
        starters = self.starters()
        lines = [self.player(p, s, True) for s, p in starters]
        bench = sorted([p for p in self.roster if p["slotId"] == 20], key=lambda p: -(p.get("proj") or 0))
        lines += [self.player(p, 20, False) for p in bench]
        for i, l in enumerate(lines):
            nxt = lines[i + 1] if i + 1 < len(lines) else None
            l["sheet"]["next"] = {"playerId": nxt["playerId"], "surname": surname(nxt["name"])} if nxt else None
        wire_lines = []
        for pid in self.text["wire"].get("candidates", []):
            w = self.wire.get(pid)
            if w:
                wire_lines.append(self.player(w, None, False, fa=True))
        as_set = sum(p.get("proj") or 0 for _, p in starters)
        best = sum(p.get("proj") or 0 for _, p in self.best)
        k = self.now.astimezone(CDMX)
        state = "BEFORE INACTIVES" if not any(self.kickoff(p) and self.kickoff(p) <= self.now for p in self.roster) else "GAMES UNDER WAY"
        return {
            "schema": SCHEMA,
            "edition": {"id": f"{self.lid}-w{self.week}-{self.now.strftime('%Y%m%dT%H%MZ')}",
                        "leagueId": self.lid, "leagueName": self.lg["settings"]["name"],
                        "teamName": self.mine["name"],
                        "label": f"{self.lg['settings']['name'].split()[0].upper()} EDITION", "week": self.week,
                        "record": f"{self.mine['record']['wins']}–{self.mine['record']['losses']}",
                        "dateline": k.strftime("%A, %B %-d, %Y").upper(), "time": k.strftime("%H:%M"),
                        "state": state, "generatedAt": self.now.strftime("%Y-%m-%dT%H:%M:%SZ"), "dryRun": dry_run},
            "chart": {"slots": self.slots, "slotsSource": self.slots_source, "currentWeek": self.week},
            "front": self.front(),
            "doFirst": self.do_first(lines, wire_lines),
            "roster": {"deck": self.text["roster"]["deck"], "asSet": round(as_set, 1), "best": round(best, 1),
                       "lines": lines},
            "wire": {"headline": self.text["wire"]["headline"], "deck": self.text["wire"]["deck"],
                     "candidates": wire_lines, "market": self.market(), "byes": self.bye_plan()},
            "notes": {"cannotSee": self.text["notes"]["cannotSee"], "lastWeek": self.last_week(),
                      "sources": self.text["notes"]["sources"]},
        }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("league")
    ap.add_argument("--dir", default=".")
    ap.add_argument("--out")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    text = load(os.path.join(a.dir, f"text-{a.league}.json"))
    ed = Edition(a.league, a.dir, text).build(a.dry_run)
    out = a.out or os.path.join(a.dir, f"edition-{a.league}.json")
    with open(out, "w") as f:
        json.dump(ed, f, indent=1, ensure_ascii=False)
    print(f"wrote {out}: {len(ed['roster']['lines'])} roster lines, {len(ed['wire']['candidates'])} wire, "
          f"{len(ed['doFirst']['cards'])} do-first cards, chart slots {ed['chart']['slots']} ({ed['chart']['slotsSource']})")


if __name__ == "__main__":
    main()
