#!/usr/bin/env python3
"""Lineup check — the first thing every brief reports.

ESPN's projection sets the lineup. Tested on the full 2025 season it picked
the better of two players 65.2% of the time, and nothing statistical beat it:
adding recent volume made it worse at every weight, and so did correcting it
by a player's past bias (BACKTEST-2025.md). So this tool does three things
and no more:

  1. Compares the lineup as SET against the projection lineup.
  2. Labels every difference: a real projection gap, or a COIN FLIP — within
     two points, where even ESPN is right only 56% of the time.
  3. Proposes a CONTINGENCY for every starter whose status is in doubt: who
     replaces him, and when to check — 90 minutes before his kickoff, when
     inactives are announced.

Deliberately absent: volume, last week's points, reliability, game state.
Each was tested and rejected. The only thing that should move a lineup away
from what this prints is news ESPN has not priced yet — and that comes from
research, not from here.

Usage:
    python3 lineup_check.py <leagueId>
"""
import datetime
import json
import sys
import urllib.error
import urllib.request

CDN = "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main"

# Slot id -> positions it accepts. Flex ids differ by league configuration,
# which is why the slots are read from each league's settings.
SLOTS = {0: {"QB"}, 2: {"RB"}, 4: {"WR"}, 6: {"TE"}, 16: {"DST"}, 17: {"K"},
         3: {"RB", "WR"}, 5: {"WR", "TE"}, 23: {"RB", "WR", "TE"},
         7: {"QB", "RB", "WR", "TE"}}
SLOT_NAME = {0: "QB", 2: "RB", 4: "WR", 6: "TE", 3: "FLEX", 5: "WR/TE",
             23: "FLEX", 7: "SUPERFLEX", 16: "D/ST", 17: "K"}

COIN_FLIP = 2.0
UNAVAILABLE = {"OUT", "INJURY_RESERVE", "SUSPENSION"}
IN_DOUBT = {"QUESTIONABLE", "DOUBTFUL", "DAY_TO_DAY"}
INACTIVES_BEFORE = datetime.timedelta(minutes=90)
# Mexico City has had no daylight saving since 2022.
CDMX = datetime.timezone(datetime.timedelta(hours=-6))


def get(path):
    try:
        with urllib.request.urlopen(f"{CDN}/{path}", timeout=30) as r:
            return json.loads(r.read().decode())
    except (urllib.error.HTTPError, ValueError):
        return None


def status(p):
    return (p.get("injury") or "ACTIVE").upper()


def kickoff(league, p):
    t = (league.get("proTeams") or {}).get(str(p.get("proTeamId")))
    if not t or not t.get("kickoff"):
        return None
    return datetime.datetime.fromisoformat(t["kickoff"].replace("Z", "+00:00"))


def locked(league, p, now):
    k = kickoff(league, p)
    return bool(k and k <= now)


def expert(p):
    """ESPN's expert weekly rank and its range. Shown, never used to decide."""
    r = p.get("rank")
    if not r:
        return ""
    best, worst = p.get("rankLow"), p.get("rankHigh")
    rng = f" ({best}-{worst})" if best and worst and best != worst else ""
    return f"{p['pos']}{r}{rng}"


def slot_list(league):
    counts = {int(k): v for k, v in league["settings"]["lineup"].items()}
    out = [s for s, n in counts.items() for _ in range(n) if s in SLOTS]
    # Most restrictive first, so a flex never takes a player a dedicated slot
    # needed.
    return sorted(out, key=lambda s: len(SLOTS[s]))


def best_lineup(league, roster, now=None):
    """Projection lineup as [(slotId, player)], skipping the unavailable.

    A locked player — his game has started — stays where he was set, because
    nothing can move him now.
    """
    now = now or datetime.datetime.now(datetime.timezone.utc)
    slots = slot_list(league)
    used, picks = set(), []
    for p in roster:
        if locked(league, p, now) and p["slotId"] in slots:
            slots.remove(p["slotId"])
            used.add(p["id"])
            picks.append((p["slotId"], p))
    for s in slots:
        cands = [p for p in roster if p["id"] not in used
                 and p["pos"] in SLOTS[s]
                 and status(p) not in UNAVAILABLE
                 and not locked(league, p, now)
                 and p.get("proj") is not None]
        if cands:
            pick = max(cands, key=lambda p: p["proj"])
            used.add(pick["id"])
            picks.append((s, pick))
    return picks


def slot_order(sp):
    order = list(SLOT_NAME)
    return order.index(sp[0]) if sp[0] in order else 99


def main(lid):
    league = get(f"league-{lid}.json")
    if not league:
        sys.exit(f"no league file for {lid}")
    now = datetime.datetime.now(datetime.timezone.utc)
    mine = [t for t in league["teams"] if t["isMine"]][0]
    roster = mine["roster"]

    best = best_lineup(league, roster, now)
    best_ids = {p["id"] for _, p in best}
    as_set = [p for p in roster if p["slotId"] not in (20, 21)]
    set_ids = {p["id"] for p in as_set}
    set_total = sum(p.get("proj") or 0 for p in as_set)
    best_total = sum(p.get("proj") or 0 for _, p in best)

    print(f"LINEUP CHECK — {league['settings'].get('name', lid)}  "
          f"week {league['scoringPeriod']}")
    print(f"as set {set_total:.1f} projected   projection lineup {best_total:.1f}"
          f"   gap {best_total - set_total:+.1f}\n")

    print("PROJECTION LINEUP")
    for s, p in sorted(best, key=slot_order):
        notes = [x for x in (status(p) if status(p) != "ACTIVE" else "",
                             "LOCKED" if locked(league, p, now) else "") if x]
        print(f"  {SLOT_NAME.get(s, s):<9} {p['name']:<24} {p.get('proj') or 0:>5.1f}  "
              f"{expert(p):<15} {' '.join(notes)}")
    print()

    changes = []
    for b in [p for p in roster if p["id"] in best_ids and p["id"] not in set_ids]:
        out = [p for p in as_set if p["id"] not in best_ids]
        rivals = [p for p in out if p["pos"] == b["pos"]] or out
        if not rivals:
            continue
        s = min(rivals, key=lambda p: p.get("proj") or 0)
        gap = (b.get("proj") or 0) - (s.get("proj") or 0)
        if status(s) in UNAVAILABLE:
            label = f"MUST CHANGE — {s['name']} is {status(s)}"
        elif gap < COIN_FLIP:
            label = "COIN FLIP — take the projection, write no thesis"
        else:
            label = f"PROJECTION — {gap:.1f} points"
        changes.append((b, s, label))

    if not changes:
        print("The lineup as set IS the projection lineup.\n")
    else:
        print("CHANGES THE PROJECTION PREFERS")
        for b, s, label in changes:
            print(f"  start {b['name']:<22} {b.get('proj') or 0:>5.1f}   over "
                  f"{s['name']:<22} {s.get('proj') or 0:>5.1f}")
            print(f"        {label}")
        print()

    print("CONTINGENCIES")
    found = False
    # A bench player can only be one starter's fallback. If Warren and Coker
    # both went inactive, naming the same man for both would leave a slot empty.
    reserved = set()
    for slot, p in best:
        if status(p) not in IN_DOUBT or locked(league, p, now):
            continue
        found = True
        fb = [q for q in roster if q["id"] not in best_ids
              and q["id"] not in reserved
              and q["pos"] in SLOTS[slot]
              and status(q) not in UNAVAILABLE
              and not locked(league, q, now)
              and q.get("proj") is not None]
        fallback = max(fb, key=lambda q: q["proj"]) if fb else None
        if fallback:
            reserved.add(fallback["id"])
        k = kickoff(league, p)
        check = ((k - INACTIVES_BEFORE).astimezone(CDMX).strftime("%a %H:%M") + " CDMX"
                 if k else "before kickoff")
        then = (f"start {fallback['name']} ({fallback['proj']:.1f})" if fallback
                else "NOBODY ELIGIBLE on the bench — find one on the wire")
        print(f"  {p['name']} — {status(p)}")
        print(f"     if inactive at {check} -> {then} in his {SLOT_NAME.get(slot, slot)} slot")
    if not found:
        print("  none — no projected starter is in doubt")
    print()

    print("Only news ESPN has not priced — a ruling after the projection was set,")
    print("a late inactive, a quarterback change — moves the lineup away from this.")
    print("Never volume, never last week's points.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: lineup_check.py <leagueId>")
    main(sys.argv[1])
