#!/usr/bin/env python3
"""Player report — the same five questions for every rostered player.

A player's week depends on the players he shares touches with. Jaylen Warren
carried 55% of Pittsburgh's backfield in week 2 while Rico Dowdle took 35%;
Dowdle was OUT for week 3 and Warren QUESTIONABLE. If Warren plays, most of
Dowdle's volume is his. If he sits, he scores nothing. Nothing used to put
those facts side by side.

For each player, from data:

  AVAILABILITY  his own designation
  ROLE          his share of his team's volume, week by week — description,
                never a score (BACKTEST-2025.md: beating a projection is not
                a trait; a back's usage share is, and it is shown as context)
  COMPETITION   teammates sharing that volume, their share and status, and
                what their absence frees up
  PASSER        for anyone who catches passes: who is throwing, and whether
                that has changed or is in doubt
  BASELINE      whether the projection lineup starts him

plus FLAGS that say what to research, and ESPN's expert weekly rank with its
range — shown, never used to decide.

The tool never writes a verdict. Research does, on news ESPN has not priced.

Shares come from the week archive's pool: every rostered player plus the top
of the wire. Deep reserves are missing, so shares run slightly high. They are
right about who leads and by how much, which is the question.

Needs lineup_check.py in the same directory — both are fetched in Step 3.5.

Usage:
    python3 player_report.py <leagueId>
"""
import datetime
import sys

try:
    from lineup_check import best_lineup, expert, get, kickoff, status, SLOT_NAME, CDMX
except ImportError:
    sys.exit("player_report.py needs lineup_check.py beside it — fetch both (Step 3.5)")

COMPETITOR_SHARE = 0.15
ROLE_SHIFT = 0.10
OUT_LIKE = {"OUT", "DOUBTFUL", "INJURY_RESERVE", "SUSPENSION"}
IN_DOUBT = {"QUESTIONABLE", "DAY_TO_DAY"}
POS_ID = {1: "QB", 2: "RB", 3: "WR", 4: "TE"}


def main(lid):
    league = get(f"league-{lid}.json")
    if not league:
        sys.exit(f"no league file for {lid}")
    current = league["scoringPeriod"]
    now = datetime.datetime.now(datetime.timezone.utc)

    weeks, pools = [], {}
    for wk in range(1, current):
        a = get(f"week-{lid}-{wk}.json")
        if a and a.get("pool"):
            weeks.append(wk)
            pools[wk] = {p["id"]: p for p in a["pool"]}

    status_of = {}
    for t in league["teams"]:
        for p in t["roster"]:
            status_of[p["id"]] = status(p)
    for w in league.get("wire", []):
        status_of.setdefault(w["id"], status(w))

    mine = [t for t in league["teams"] if t["isMine"]][0]
    baseline = {p["id"]: s for s, p in best_lineup(league, mine["roster"], now)}

    def touched(line):
        return any((line.get(k) or 0) for k in ("carries", "targets", "passAtt"))

    def series(pid, key):
        """Per-week values; None for a week with no touches. ESPN records a
        row of zeros for a player who dressed and never touched the ball, and
        reading it as zero flagged Kamara ROLE_UP for simply returning."""
        return [((pools[wk][pid].get(key) or 0)
                 if pid in pools[wk] and touched(pools[wk][pid]) else None)
                for wk in weeks]

    def played(xs):
        return [x for x in xs if x is not None]

    def name_of(pid):
        for wk in reversed(weeks):
            if pid in pools[wk]:
                return pools[wk][pid]["name"]
        return str(pid)

    def pos_of(pid):
        for wk in reversed(weeks):
            if pid in pools[wk]:
                return POS_ID.get(pools[wk][pid].get("pos"))
        return None

    def team_ids(team, positions):
        return {pid for wk in weeks for pid, p in pools[wk].items()
                if p.get("proTeamId") == team and POS_ID.get(p.get("pos")) in positions}

    def shares(team, positions, key):
        ids = team_ids(team, positions)
        totals = [sum((pools[wk].get(i) or {}).get(key) or 0 for i in ids) for wk in weeks]
        return {i: [None if v is None else (v / t if t else 0.0)
                    for v, t in zip(series(i, key), totals)] for i in ids}

    def fmt(xs, pct=False):
        return " -> ".join("-" if x is None else
                           (f"{x * 100:.0f}%" if pct else
                            (str(int(x)) if float(x).is_integer() else f"{x:.1f}"))
                           for x in xs)

    def role_flag(sh, vol):
        """A share change only counts when his own volume moved the same way:
        Achane went 92% -> 80% of Miami's carries while his carries went 11 ->
        20. That is a team running more, not a role shrinking."""
        sh, vol = played(sh), played(vol)
        if len(sh) < 2:
            return None
        d = sh[-1] - sh[0]
        if d <= -ROLE_SHIFT and vol[-1] <= vol[0]:
            return "ROLE_DOWN"
        if d >= ROLE_SHIFT and vol[-1] >= vol[0]:
            return "ROLE_UP"
        return None

    print(f"PLAYER REPORT — {league['settings'].get('name', lid)}  week {current}")
    print(f"archive weeks {weeks or 'none yet'}"
          f"{'  (thin sample)' if len(weeks) < 4 else ''}\n")

    order = {"QB": 0, "RB": 1, "WR": 2, "TE": 3, "K": 4, "DST": 5}
    roster = sorted(mine["roster"],
                    key=lambda p: (p["id"] not in baseline, order.get(p["pos"], 9)))
    team_checks = {}

    for p in roster:
        pid, pos, team = p["id"], p["pos"], p.get("proTeamId")
        own = status(p)
        flags, research = [], []
        abbrev = ((league.get("proTeams") or {}).get(str(team)) or {}).get("abbrev", "?")
        team_checks.setdefault(abbrev, set()).add(p["name"])
        k = kickoff(league, p)
        when = k.astimezone(CDMX).strftime("%a %H:%M") if k else "?"

        slot = baseline.get(pid)
        print(f"{pos:<3} {p['name']:<24} {abbrev:<4} proj {p.get('proj') or 0:>5.1f}   "
              f"{'START ' + SLOT_NAME.get(slot, str(slot)) if slot is not None else 'bench':<11} "
              f"{expert(p):<15} kickoff {when}")
        print(f"    availability  {own}")
        if own in IN_DOUBT:
            flags.append("OWN_QUESTIONABLE")
            research.append("practice trend; inactives 90 min before kickoff")
        elif own in OUT_LIKE:
            flags.append("OWN_OUT")
            research.append("confirm, and who takes his lineup spot")

        if pos in ("K", "DST") or not team or not weeks:
            print(f"    flags         {' '.join(flags) or '-'}")
            for r in research:
                print(f"    research      {r}")
            print()
            continue

        if pos == "RB":
            car = shares(team, {"RB"}, "carries")
            mine_sh = car.get(pid, [None] * len(weeks))
            print(f"    role          carries {fmt(series(pid, 'carries'))} "
                  f"({fmt(mine_sh, True)} of backfield)   targets {fmt(series(pid, 'targets'))}")
            primary, key, label = car, "carries", "carries"
            role = role_flag(mine_sh, series(pid, "carries"))
        elif pos == "QB":
            print(f"    role          pass att {fmt(series(pid, 'passAtt'))}   "
                  f"carries {fmt(series(pid, 'carries'))}")
            primary, key, label, role = {}, None, None, None
        else:
            tgt = shares(team, {"RB", "WR", "TE"}, "targets")
            mine_sh = tgt.get(pid, [None] * len(weeks))
            print(f"    role          targets {fmt(series(pid, 'targets'))} "
                  f"({fmt(mine_sh, True)} of team)")
            primary, key, label = tgt, "targets", "targets"
            role = role_flag(mine_sh, series(pid, "targets"))
        if role:
            flags.append(role)
            research.append("why his share moved — injury, depth chart, or noise")

        if key:
            rivals = []
            for other, sh in primary.items():
                if other == pid or not played(sh) or max(played(sh)) < COMPETITOR_SHARE:
                    continue
                if pos != "RB" and pos_of(other) == "RB":
                    continue  # a back's targets are not a receiver's rival
                rivals.append((other, sh, status_of.get(other, "ACTIVE")))
            rivals.sort(key=lambda r: -(played(r[1]) or [0])[-1])
            if not rivals:
                print("    competition   none above 15% share")
            for other, sh, st in rivals[:3]:
                note = ""
                if st in OUT_LIKE:
                    vol = (played(series(other, key)) or [0])[-1]
                    extra = ""
                    if pos == "RB":
                        t = (played(series(other, "targets")) or [0])[-1]
                        extra = f" and ~{t:.0f} targets" if t else ""
                    note = f"  -> ~{vol:.0f} {label}{extra} a week vacated"
                    flags.append("VACATED")
                    research.append(f"confirm {name_of(other)} inactive; who inherits; "
                                    f"did ESPN already adjust?")
                elif st in IN_DOUBT:
                    flags.append("RIVAL_QUESTIONABLE")
                    research.append(f"{name_of(other)}: if he sits, this role grows")
                print(f"    competition   {name_of(other):<22} {fmt(sh, True):<17} {st}{note}")

        if pos in ("RB", "WR", "TE"):
            qbs = team_ids(team, {"QB"})
            led = []
            for wk in weeks:
                top = max(qbs, key=lambda q: (pools[wk].get(q) or {}).get("passAtt") or 0,
                          default=None)
                if top and (pools[wk].get(top) or {}).get("passAtt"):
                    led.append(top)
            if led:
                last = led[-1]
                st = status_of.get(last, "ACTIVE")
                changed = len(set(led)) > 1
                print(f"    passer        {name_of(last)}  {st}"
                      f"{'   (changed during the archive)' if changed else ''}")
                if st in OUT_LIKE:
                    flags.append("PASSER_OUT")
                    research.append(f"who replaces {name_of(last)}; is it a downgrade")
                elif st in IN_DOUBT:
                    flags.append("PASSER_QUESTIONABLE")
                    research.append(f"{name_of(last)}'s status")
                if changed:
                    flags.append("QB_CHANGE")
                    research.append("who starts at quarterback now")

        print(f"    flags         {' '.join(dict.fromkeys(flags)) or '-'}")
        for r in dict.fromkeys(research):
            print(f"    research      {r}")
        print()

    print("RESEARCH BY TEAM — one search on each team's injury report and news")
    print("covers everyone listed. Every team, every run; \"no change\" is an answer.")
    for abbrev in sorted(team_checks):
        print(f"  {abbrev:<4} {', '.join(sorted(team_checks[abbrev]))}")
    print(f"\n{len(team_checks)} teams. The tool never writes a verdict.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: player_report.py <leagueId>")
    main(sys.argv[1])
