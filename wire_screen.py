#!/usr/bin/env python3
"""wire_screen.py — rank every available player by opportunity, for research.

A screen, not a verdict. It decides who gets researched; research decides who
gets added. Scored per position, relative to the other available players:

  35%  share of team volume last week   (carries for RBs, targets otherwise)
  20%  touches last week
  20%  change in share against his own earlier weeks
  25%  vacated opportunity — share he inherits from injured teammates
  +    ownership change, as a tie-breaker only

Points per touch is never scored: efficiency does not persist (2025 backtest).
It is flagged instead — big points on few touches is the fluke signature.

Available = in the stat pool and on no roster in this league (the published
wire list is partial). Quarterbacks rank by volume and its change, and every
new starter is listed regardless of rank. Own-roster exposure lists your
players whose teammate is out — never name one of them as a drop.

TWO PASSES. Pass 1 uses ESPN's injury designations, which lag on Mondays. After
researching the week's injuries, run again with --injured "Name, Name" so
vacated opportunity counts what research confirmed.

Usage:  python3 wire_screen.py <leagueId> [--dir .] [--injured "A, B"] [--json out.json]
"""
import argparse
import json
import os
import statistics as st
import urllib.request

POSN = {1: "QB", 2: "RB", 3: "WR", 4: "TE"}
OUTS = {"OUT", "INJURY_RESERVE", "DOUBTFUL", "SUSPENSION"}
REPO_RAW = "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main"
TOP = {"RB": 5, "WR": 5, "TE": 5, "QB": 3}


def load(path, url=None):
    if not os.path.exists(path) and url:
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                open(path, "wb").write(r.read())
        except Exception:
            return None
    try:
        return json.load(open(path))
    except Exception:
        return None


def touches(l, pos):
    if pos == "QB":
        return (l.get("passAtt") or 0) + (l.get("carries") or 0)
    return (l.get("targets") or 0) + (l.get("carries") or 0)


def screen(lid, base, injured=()):
    lg = load(os.path.join(base, f"league-{lid}.json"))
    week = lg["scoringPeriod"]
    # The last three weeks with stat lines: this week's live archive if present.
    weeks = []
    for w in range(max(1, week - 3), week + 1):
        name = f"week-{lid}-{w}-live.json" if w == week else f"week-{lid}-{w}.json"
        a = load(os.path.join(base, name), f"{REPO_RAW}/{name}")
        if a and a.get("pool"):
            weeks.append((w, {p["id"]: p for p in a["pool"]}))
    weeks = weeks[-3:]
    if not weeks:
        raise SystemExit("no stat lines found — nothing to screen")
    pools = [p for _, p in weeks]
    last = len(pools) - 1

    status = {}
    for t in lg["teams"]:
        for p in t["roster"]:
            status[p["id"]] = (p.get("injury") or "ACTIVE").upper()
    for w in lg.get("wire", []):
        status.setdefault(w["id"], (w.get("injury") or "ACTIVE").upper())
    info = {}
    for pool in pools:
        for pid, l in pool.items():
            if POSN.get(l.get("pos")):
                info[pid] = (POSN[l["pos"]], l.get("proTeamId"), l["name"])
    forced = {n.strip().lower() for n in injured if n.strip()}
    for pid, (_, _, nm) in info.items():
        if nm.lower() in forced:
            status[pid] = "OUT"

    def share(pid, pos, k):
        l = pools[k].get(pid)
        if not l or pos == "QB" or not touches(l, pos):
            return None
        key = "carries" if pos == "RB" else "targets"
        grp = {"RB"} if pos == "RB" else {"RB", "WR", "TE"}
        tot = sum((x.get(key) or 0) for x in pools[k].values()
                  if x.get("proTeamId") == l.get("proTeamId") and POSN.get(x.get("pos")) in grp)
        return (l.get(key) or 0) / tot if tot else None

    def usual_share(pid, pos):
        """An injured player's normal share: his best of the weeks he played —
        the week he got hurt understates it."""
        xs = [s for s in (share(pid, pos, k) for k in range(len(pools))) if s is not None]
        return max(xs) if xs else None

    inherit = {}
    for pid, (pos, team, nm) in info.items():
        if pos == "QB" or status.get(pid) not in OUTS:
            continue
        s = usual_share(pid, pos)
        if not s or s < 0.15:
            continue
        mates = [(q, share(q, pos, last) or usual_share(q, pos) or 0) for q, (p2, t2, _) in info.items()
                 if t2 == team and p2 == pos and q != pid and status.get(q) not in OUTS]
        tot = sum(x for _, x in mates)
        for q, x in mates:
            inherit.setdefault(q, []).append((nm, s * (x / tot if tot > 1e-9 else 1 / len(mates))))

    rostered = {p["id"] for t in lg["teams"] for p in t["roster"]}
    wire = {w["id"]: w for w in lg.get("wire", [])}
    teams = lg["proTeams"]

    def passer(team, k):
        q = [((x.get("passAtt") or 0), x["name"]) for x in pools[k].values()
             if x.get("proTeamId") == team and x.get("pos") == 1]
        q = max(q) if q else (0, None)
        return q[1] if q[0] >= 10 else None

    rows = []
    for pid, (pos, team, nm) in info.items():
        if pid in rostered or status.get(pid) in OUTS:
            continue
        l = pools[last].get(pid)
        t_now = touches(l, pos) if l else 0
        inh = sum(v for _, v in inherit.get(pid, []))
        if not t_now and inh < 0.15:
            continue
        s_now = share(pid, pos, last)
        before = [s for s in (share(pid, pos, k) for k in range(last)) if s is not None]
        t_before = [touches(pools[k][pid], pos) for k in range(last) if pid in pools[k] and touches(pools[k][pid], pos)]
        new_qb = (pos == "QB" and last > 0 and passer(team, last) == nm and passer(team, last - 1) not in (nm, None))
        w = wire.get(pid, {})
        eff = (l["pts"] / t_now) if (l and t_now) else None
        rows.append({
            "id": pid, "name": nm, "pos": pos, "team": teams.get(str(team), {}).get("abbrev"),
            "touches": [touches(p[pid], pos) if pid in p else None for p in pools],
            "points": [round(p[pid]["pts"], 1) if pid in p else None for p in pools],
            "share": s_now, "shareBefore": st.mean(before) if before else None,
            "touchesBefore": st.mean(t_before) if t_before else 0,
            "inherits": round(inh, 3), "inheritsFrom": [n for n, _ in inherit.get(pid, [])],
            "efficiencyFlag": bool(eff and t_now < 8 and l["pts"] >= 15),
            "newStarter": new_qb, "owned": w.get("owned"), "ownedChange": w.get("ownedChange") or 0,
            "onPublishedWire": pid in wire, "status": w.get("status") or ("FREE AGENT" if pid not in wire else "FA"),
        })

    def z(v):
        m, d = st.mean(v), st.pstdev(v) or 1
        return [(x - m) / d for x in v]

    out = {}
    for pos, n in TOP.items():
        R = [r for r in rows if r["pos"] == pos]
        if not R:
            out[pos] = []
            continue
        if pos == "QB":
            a = z([r["touches"][-1] or 0 for r in R])
            b = z([(r["touches"][-1] or 0) - r["touchesBefore"] for r in R])
            sc = [.6 * x + .4 * y for x, y in zip(a, b)]
        else:
            a = z([r["share"] or 0 for r in R])
            b = z([r["touches"][-1] or 0 for r in R])
            c = z([(r["share"] or 0) - (r["shareBefore"] if r["shareBefore"] is not None else (r["share"] or 0)) for r in R])
            d = z([r["inherits"] for r in R])
            sc = [.35 * x + .20 * y + .20 * w_ + .25 * v for x, y, w_, v in zip(a, b, c, d)]
        for r, s_ in zip(R, sc):
            r["score"] = round(s_ + 0.05 * min(r["ownedChange"], 10), 3)
        ranked = sorted(R, key=lambda r: -r["score"])
        top = ranked[:n]
        if pos == "QB":   # every new starter is researched, whatever his rank
            top += [r for r in ranked[n:] if r["newStarter"]]
        out[pos] = top

    mine = next(t for t in lg["teams"] if t["isMine"])
    exposure = [{"name": p["name"], "teammatesOut": [x for x, _ in inherit[p["id"]]]}
                for p in mine["roster"] if p["id"] in inherit]
    return {"leagueId": str(lid), "week": week, "weeks": [w for w, _ in weeks],
            "forcedInjuries": sorted(forced), "candidates": out, "exposure": exposure}


def report(res):
    print(f"WIRE SCREEN · league {res['leagueId']} · week {res['week']} · stat weeks {res['weeks']}"
          + (f" · research-confirmed out: {', '.join(res['forcedInjuries'])}" if res["forcedInjuries"] else " · pass 1 (ESPN designations)"))
    print("OWN-ROSTER EXPOSURE (never name as a drop):",
          ", ".join(f"{e['name']} ({', '.join(e['teammatesOut'])} out)" for e in res["exposure"]) or "none")
    for pos, R in res["candidates"].items():
        print(f"\n{pos}")
        for r in R:
            flags = [f"INHERITS {100 * r['inherits']:.0f}% ({', '.join(r['inheritsFrom'])})" if r["inherits"] >= .10 else "",
                     "NEW STARTER" if r["newStarter"] else "", "EFFICIENCY-DRIVEN" if r["efficiencyFlag"] else "",
                     "not on the published wire" if not r["onPublishedWire"] else "",
                     f"ownership {r['ownedChange']:+.1f}" if abs(r["ownedChange"]) >= .5 else ""]
            s = f"{100 * r['share']:.0f}%" if r["share"] is not None else "—"
            b = f" (was {100 * r['shareBefore']:.0f}%)" if r["shareBefore"] is not None else ""
            print(f"  {r['name']:<22} {r['team'] or '?':<4} touches {r['touches']} points {r['points']} share {s}{b}"
                  f"  {' · '.join(f for f in flags if f)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("league")
    ap.add_argument("--dir", default=".")
    ap.add_argument("--injured", default="", help="research-confirmed injuries, comma-separated names")
    ap.add_argument("--json")
    a = ap.parse_args()
    res = screen(a.league, a.dir, a.injured.split(",") if a.injured else ())
    report(res)
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
