#!/usr/bin/env python3
"""Waiver Wire Analysis — the data half of the brief's waiver section.

Compares the wire against my roster on opportunity, production and trend,
and SURFACES QUESTIONS rather than answering them. Two weeks of data cannot
tell a slump from a role change; research answers that. This finds where to
look.

Built from four prototypes that each failed instructively:

  - Raw opportunity said C.J. Stroud beats Josh Allen. Opportunity means
    something else at quarterback, so QBs are compared on points only.
  - It recommended Tyler Allgeier: 13 opportunities a game for 6.5 points,
    role falling 19 -> 7. So a shrinking role disqualifies, and volume must
    produce something.
  - It called Justin Jefferson my weakest receiver on one quiet week, and
    suggested replacing Saquon Barkley. So studs are never "movable".
  - Protecting studs by ESPN rank then hid Colston Loveland, producing 0.7
    points a game on 4 opportunities while still ranked top-24.

The last two conflict, and what resolves them is shape, not rank:

    Barkley  opportunities [17, 7]   one bad week      -> a slump
    Loveland opportunities [ 2, 3]   empty every week  -> a role problem

So "falling on my roster" means LOW IN EVERY WEEK, never low in one.

Usage:
    python3 waiver_analysis.py <leagueId>

Prints a table the brief interprets. Writes nothing.
"""
import json
import sys
import urllib.error
import urllib.request

CDN = "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main"
POS = {1: "QB", 2: "RB", 3: "WR", 4: "TE"}

# A target is worth more than a carry in full PPR: a catch is a point before
# a yard is gained.
TARGET_WEIGHT = 1.5

# Top-N at a position is never "movable". Nobody drops a top-24 back on two
# games, however quiet they were.
STUD_RANK = 24

# Opportunity below this in EVERY archived week is a role problem, not a
# slump. Per position, because a tight end's normal is a back's disaster.
ROLE_FLOOR = {"RB": 9, "WR": 7, "TE": 5}

# A wire player has to beat my weakest movable player by this margin in the
# most recent week. Smaller gaps are noise over a two-week sample.
MARGIN = 1.3


def get(path):
    try:
        with urllib.request.urlopen(f"{CDN}/{path}", timeout=30) as r:
            return json.loads(r.read().decode())
    except (urllib.error.HTTPError, ValueError):
        return None


def main(lid):
    league = get(f"league-{lid}.json")
    if not league:
        sys.exit(f"no league file for {lid}")
    current = league["scoringPeriod"]

    # Every archived week, not a hardcoded pair. The sample grows weekly and
    # the analysis should grow with it.
    weeks, pool = [], {}
    for wk in range(1, current):
        a = get(f"week-{lid}-{wk}.json")
        if not a or not a.get("pool"):
            continue
        weeks.append(wk)
        for p in a["pool"]:
            pool.setdefault(p["id"], {})[wk] = p
    if not weeks:
        sys.exit("no archived weeks with a pool yet")

    mine = [t for t in league["teams"] if t["isMine"]][0]
    rostered = {p["id"] for t in league["teams"] for p in t["roster"]}
    rank = {p["id"]: p.get("rank") or 999 for p in mine["roster"]}

    def opp(line):
        if not line:
            return 0.0
        return (line.get("targets") or 0) * TARGET_WEIGHT + (line.get("carries") or 0)

    def profile(pid):
        lines = pool.get(pid, {})
        played = [w for w in weeks if w in lines]
        if not played:
            return None
        first = lines[played[0]]
        ops = [opp(lines[w]) for w in played]
        pts = [lines[w]["pts"] for w in played]
        return {
            "id": pid,
            "name": first["name"],
            "pos": POS.get(first["pos"], "?"),
            "weeks": played,
            "ops": [round(o, 1) for o in ops],
            "pts": pts,
            "last": ops[-1],
            "ppg": sum(pts) / len(pts),
            "trend": ops[-1] - ops[0] if len(ops) > 1 else 0.0,
        }

    n = len(weeks)
    print(f"WAIVER WIRE ANALYSIS — {league['settings'].get('name', lid)}")
    print(f"weeks {weeks}  ({n} week{'s' if n != 1 else ''} of data"
          f"{' — this is thin' if n < 4 else ''})\n")

    rising_total = 0
    falling = []

    for pos in ("RB", "WR", "TE"):
        mine_here = [profile(p["id"]) for p in mine["roster"] if p["pos"] == pos]
        mine_here = [m for m in mine_here if m]

        # FALLING: low in every week, regardless of rank. This is the Loveland
        # check, and it must not depend on rank because rank is exactly what
        # was hiding him.
        for m in mine_here:
            if len(m["ops"]) >= 2 and all(o < ROLE_FLOOR[pos] for o in m["ops"]):
                falling.append((pos, m))

        movable = [m for m in mine_here if rank.get(m["id"], 999) > STUD_RANK]
        print(f"{pos}")
        if not movable:
            print("   every rostered player is top-24 — none movable\n")
            continue
        weakest = min(movable, key=lambda m: (m["last"], m["ppg"]))
        print(f"   weakest movable: {weakest['name']}  "
              f"ops {weakest['ops']}  {weakest['ppg']:.1f} ppg")

        rising = []
        for pid in pool:
            if pid in rostered:
                continue
            c = profile(pid)
            if not c or c["pos"] != pos or len(c["weeks"]) < min(2, n):
                continue
            if c["trend"] < 0:
                continue  # a shrinking role, whatever the average says
            if c["last"] < weakest["last"] * MARGIN:
                continue  # not a real gap
            if c["ppg"] < weakest["ppg"] * 0.8:
                continue  # volume that produces nothing
            rising.append(c)
        rising.sort(key=lambda c: -c["last"])

        if not rising:
            print("   nothing on the wire clears the bar\n")
            continue
        rising_total += len(rising)
        for c in rising[:4]:
            print(f"   RISING  {c['name']:<22} ops {c['ops']}  "
                  f"{c['ppg']:.1f} ppg  trend {c['trend']:+.1f}")
        print()

    print("FALLING ON MY ROSTER — low opportunity in every week")
    if not falling:
        print("   none\n")
    for pos, m in falling:
        r = rank.get(m["id"], 999)
        note = f"  (ESPN still ranks him {r} — rank and usage disagree)" if r <= STUD_RANK else ""
        print(f"   {pos}  {m['name']:<22} ops {m['ops']}  {m['ppg']:.1f} ppg{note}")
    print()

    if rising_total == 0 and not falling:
        print("VERDICT: NOTHING WORTH ADDING. The wire does not beat this "
              "roster on usage.")
    else:
        print("VERDICT: questions, not answers. Every RISING and FALLING line "
              "needs a role reason from research before it becomes a move. "
              "Data alone is the chasing pattern.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: waiver_analysis.py <leagueId>")
    main(sys.argv[1])
