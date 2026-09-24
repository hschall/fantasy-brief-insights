#!/usr/bin/env python3
"""Lineup check — the first thing every brief reports.

All four losses so far were winnable with the roster already owned: 134
points left on benches, every margin under 16. The lineup is the highest-value
decision in the week and it was getting the least attention.

This compares the lineup as currently SET against the lineup the projections
prefer, and classifies every slot where they differ:

  AGREE      projection and volume prefer the same player. Just set it.
  VOLUME     a clear opportunity gap, not shrinking. Follow volume, even
             against a small projection edge. (Coker 9 vs McLaurin 4.)
  COIN FLIP  close on both. Default to projection; do NOT write a thesis.
             (Warren and Tuten, 16 and 16.)

It deliberately does not consider last week's POINTS anywhere. That is the
single most expensive mistake this system has made — the IPADE quarterback
slot lost 42.8 points in two weeks by switching to whoever scored last.

Usage:
    python3 lineup_check.py <leagueId>
"""
import json
import sys
import urllib.error
import urllib.request

CDN = "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main"
POS = {1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "DST"}
BASE = {0: {"QB"}, 2: {"RB"}, 4: {"WR"}, 6: {"TE"}, 16: {"DST"}, 17: {"K"}}
FLEX = {3: {"RB", "WR"}, 23: {"RB", "WR", "TE"}, 5: {"WR", "TE"}}
SLOT_NAME = {0: "QB", 2: "RB", 4: "WR", 6: "TE", 16: "DST", 17: "K",
             3: "FLEX", 23: "FLEX+", 5: "WR/TE"}
TARGET_WEIGHT = 1.5

# Within this many projected points, projection alone does not decide.
PROJ_CLOSE = 2.0
# A volume lead has to be this large, relative, to override projection.
VOLUME_GAP = 1.5


def get(path):
    try:
        with urllib.request.urlopen(f"{CDN}/{path}", timeout=30) as r:
            return json.loads(r.read().decode())
    except (urllib.error.HTTPError, ValueError):
        return None


def main(lid):
    league = get(f"league-{lid}.json")
    current = league["scoringPeriod"]
    mine = [t for t in league["teams"] if t["isMine"]][0]

    slots = {int(k): v for k, v in league["settings"]["lineup"].items()}
    elig = dict(BASE)
    elig.update({k: v for k, v in FLEX.items() if k in slots})

    # Most recent archived week's volume, for the VOLUME case.
    last_opp = {}
    for wk in range(current - 1, 0, -1):
        a = get(f"week-{lid}-{wk}.json")
        if a and a.get("pool"):
            for p in a["pool"]:
                last_opp[p["id"]] = ((p.get("targets") or 0) * TARGET_WEIGHT
                                     + (p.get("carries") or 0))
            source_week = wk
            break
    else:
        source_week = None

    players = [{**p, "posName": POS.get(p["pos"], p["pos"])}
               for p in mine["roster"]]
    players = [p for p in players if p["posName"] in {"QB", "RB", "WR", "TE", "K", "DST"}]

    # Projection-best lineup, filling the most restrictive slots first.
    order = sorted([s for s in slots for _ in range(slots[s]) if s in elig],
                   key=lambda s: len(elig[s]))
    used, best = set(), []
    for slot in order:
        c = [p for p in players if p["id"] not in used
             and p["posName"] in elig[slot] and p.get("proj") is not None]
        if not c:
            continue
        pick = max(c, key=lambda p: p["proj"])
        used.add(pick["id"])
        best.append((slot, pick))

    started = {p["id"] for p in players if p["slotId"] not in (20, 21)}
    best_ids = {p["id"] for _, p in best}

    set_total = sum(p["proj"] or 0 for p in players if p["id"] in started)
    best_total = sum(p["proj"] or 0 for _, p in best)

    print(f"LINEUP CHECK — {league['settings'].get('name', lid)}  week {current}")
    print(f"as set {set_total:.1f} projected   best by projection {best_total:.1f}"
          f"   gap {best_total - set_total:+.1f}")
    if source_week:
        print(f"volume from week {source_week}")
    print()

    benched_better = [p for p in players if p["id"] in best_ids and p["id"] not in started]
    started_worse = [p for p in players if p["id"] in started and p["id"] not in best_ids]

    if not benched_better:
        print("The lineup as set IS the projection lineup. Nothing to change.")
        return

    for b in benched_better:
        # Pair each benched player with the weakest started player he could
        # replace at a shared position.
        rivals = [s for s in started_worse if s["posName"] == b["posName"]] or started_worse
        if not rivals:
            continue
        s = min(rivals, key=lambda x: x.get("proj") or 0)
        dp = (b["proj"] or 0) - (s["proj"] or 0)
        bo, so = last_opp.get(b["id"]), last_opp.get(s["id"])

        if bo is not None and so is not None and so > 0 and bo / so >= VOLUME_GAP:
            case = "VOLUME"
            why = f"benched player out-volumed him {bo:.0f} to {so:.0f}"
        elif bo is not None and so is not None and bo > 0 and so / bo >= VOLUME_GAP:
            case = "VOLUME against projection"
            why = (f"started player out-volumed him {so:.0f} to {bo:.0f} — "
                   f"projection and volume disagree; research decides")
        elif dp < PROJ_CLOSE:
            case = "COIN FLIP"
            why = "close on projection and volume. Take projection, write no thesis"
        else:
            case = "AGREE"
            why = f"projection prefers him by {dp:.1f}"

        print(f"  START {b['name']:<22} proj {b['proj']:.1f}"
              f"{f'  opp {bo:.0f}' if bo is not None else ''}")
        print(f"  over  {s['name']:<22} proj {s['proj']:.1f}"
              f"{f'  opp {so:.0f}' if so is not None else ''}")
        print(f"        [{case}] {why}\n")

    print("Never override on last week's POINTS. Only a named role fact — an")
    print("injury, a quarterback change, a depth-chart move — justifies")
    print("starting someone the projection and volume both prefer less.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: lineup_check.py <leagueId>")
    main(sys.argv[1])
