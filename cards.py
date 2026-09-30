#!/usr/bin/env python3
"""Build cards-<leagueId>.json — a player card for every player in the league.

Every rostered player (all teams) and every player on the published wire gets
the same sheet as the Insights paper, computed by edition.py's own code, so a
card and the paper can never disagree. Players the brief researched this run —
his roster and its wire candidates — carry that research (text-<L>.json);
everyone else gets a data-only card whose words are written from data and say
so. Nothing here is written by an agent.

Usage:  python3 cards.py <leagueId> [--dir PATH] [--out PATH]
"""
import argparse
import json
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from edition import Edition, POSN, load, slot_label, surname  # noqa: E402
from lineup_check import SLOTS  # noqa: E402

f1 = lambda v: f"{v:.1f}"
DATA_ONLY = "Data only — ESPN data and the week archive; no research this run"


def latest_pool(ed):
    """The most recent week with stat lines: this week's live archive if the
    player's game is final, else the last finished week."""
    weeks = sorted(ed.pools)
    return weeks[-1] if weeks else None


def data_text(ed, p, owner, start, fa):
    """Words for a player nobody researched, written only from data."""
    pos, proj, act = p["pos"], p.get("proj") or 0, p.get("actual")
    # The most recent week in which HE played — this week's live archive has
    # everyone at zero until their game.
    w = next((wk for wk in sorted(ed.pools, reverse=True)
              if (ed.pools[wk].get(p["id"]) and ed.touches(ed.pools[wk][p["id"]], pos))), latest_pool(ed))
    pool = ed.pools.get(w, {})
    line = pool.get(p["id"])
    role = passer = competition = None
    if line and pos in ("RB", "WR", "TE"):
        key = "carries" if pos == "RB" else "targets"
        sh = ed.share(p["id"], p.get("proTeamId"), pos, w)
        role = f"{ed.touches(line, pos)} touches in week {w}" + (f", {100 * sh:.0f}% of the team's {key}" if sh else "")
        mates = [(ed.share(x["id"], x.get("proTeamId"), pos, w) or 0, x["name"]) for x in pool.values()
                 if x.get("proTeamId") == p.get("proTeamId") and POSN.get(x.get("pos")) == pos and x["id"] != p["id"]]
        mates = [m for m in mates if m[0] >= 0.15]
        if mates:
            s, n = max(mates)
            competition = f"{n}, {100 * s:.0f}% of the {key} in week {w}"
        qbs = [((x.get("passAtt") or 0), x["name"]) for x in pool.values()
               if x.get("proTeamId") == p.get("proTeamId") and x.get("pos") == 1]
        passer = max(qbs)[1] if qbs else None
    elif line and pos == "QB":
        role = f"{line.get('passAtt') or 0} attempts and {line.get('carries') or 0} carries in week {w}"
    played = act is not None and ed.finished(p)
    deck = (f"{f1(act)} against a {f1(proj)} projection." if played else
            f"Projects {f1(proj)} this week." if proj else "No projection this week.")
    reason = f"scored {f1(act)} of a {f1(proj)} projection" if played else f"projects {f1(proj)}"
    # LOGIC, templated: the comparison the numbers support, and the result if final.
    if owner is None and not fa:     # his own roster
        mine = ed.roster
        if start:
            rivals = [b for b in mine if b["slotId"] == 20 and b["pos"] in SLOTS.get(p["slotId"], set())]
            rv = max(rivals, key=lambda x: x.get("proj") or 0) if rivals else None
            head = (f"Started at {f1(proj)}, {proj - (rv.get('proj') or 0):+.1f} over {rv['name']} on the bench"
                    if rv else f"No one on the bench could take his slot; he starts by default at {f1(proj)}")
        else:
            rivals = [s for s in mine if s["slotId"] not in (20, 21) and p["pos"] in SLOTS.get(s["slotId"], set())]
            rv = min(rivals, key=lambda x: x.get("proj") or 0) if rivals else None
            head = (f"Benched at {f1(proj)}, {proj - (rv.get('proj') or 0):+.1f} against {rv['name']}, "
                    f"the weakest starter he could replace" if rv else f"Benched at {f1(proj)}")
    elif fa:
        rivals = [s for s in ed.roster if s["slotId"] not in (20, 21) and p["pos"] in SLOTS.get(s["slotId"], set())]
        rv = min(rivals, key=lambda x: x.get("proj") or 0) if rivals else None
        head = (f"A free agent at {f1(proj)}, {proj - (rv.get('proj') or 0):+.1f} against {rv['name']}, "
                f"your weakest starter he could replace" if rv else f"A free agent projecting {f1(proj)}")
    else:
        rv = None
        poss = owner + ("'" if owner.endswith("s") else "'s")
        head = f"On {poss} {'starting lineup' if start else 'bench'}, projecting {f1(proj)}"
    if rv:
        gap = abs(proj - (rv.get("proj") or 0))
        head += " — inside the 2-point coin-flip line" if gap < 2 else " — outside the coin-flip line"
    logic = head + "."
    if played:
        logic += f" He scored {f1(act)}."
    logic += " The projection sets the lineup; nothing on this card has been checked against the news."
    status = (p.get("injury") or "ACTIVE").replace("_", " ")
    return dict(deck=deck, reason=reason, flags=[], availability=status, role=role, competition=competition,
                passer=passer, checked=DATA_ONLY, vsProjection=None,
                verdict=("START" if start else "BENCH"), logic=logic, matchupGrade=None)


def build(lid, base):
    text = {}
    tp = os.path.join(base, f"text-{lid}.json")
    if os.path.exists(tp):
        text = load(tp)
    researched = text.get("players", {})
    researched_at = None
    ep = os.path.join(base, f"edition-{lid}.json")
    if os.path.exists(ep):
        researched_at = load(ep).get("edition", {}).get("generatedAt")

    ed = Edition(lid, base, {"players": {}, "unpriced": text.get("unpriced", {})})
    my_team = ed.mine["id"]
    people = []   # (player, owner name or None for mine, starting, fa)
    for t in ed.lg["teams"]:
        for p in t["roster"]:
            owner = None if t["id"] == my_team else t["name"]
            people.append((p, owner, p["slotId"] not in (20, 21), False))
    seen = {p["id"] for p, *_ in people}
    for w in ed.lg.get("wire", []):
        if w["id"] not in seen:
            people.append((w, None, False, True))
            seen.add(w["id"])
    # The published wire is only part of it: every player in the stat pool who
    # is on no roster here is available too, and gets a card.
    latest = {}
    for wk in sorted(ed.pools):
        for pid, l in ed.pools[wk].items():
            latest[pid] = l
    for pid, l in latest.items():
        pos = POSN.get(l.get("pos"))
        if pid in seen or pos not in ("QB", "RB", "WR", "TE"):
            continue
        people.append(({"id": pid, "name": l["name"], "pos": pos, "proTeamId": l.get("proTeamId"),
                        "proj": None, "injury": None}, None, False, True))
        seen.add(pid)

    # Research where the brief did it; data words for everyone else.
    for p, owner, start, fa in people:
        pid = str(p["id"])
        ed.text["players"][pid] = researched.get(pid) or data_text(ed, p, owner, start, fa)

    cards = []
    for p, owner, start, fa in people:
        pid = str(p["id"])
        try:
            line = ed.player(p, p.get("slotId") if not fa else None, start, fa=fa)
        except Exception as e:  # one bad record must not cost the file
            print(f"  skipped {p.get('name')}: {e}", file=sys.stderr)
            continue
        is_research = pid in researched
        line["researched"] = is_research
        line["researchedAt"] = researched_at if is_research else None
        line["owner"] = owner
        sh = line["sheet"]
        # A normal week estimated from a thin season projection can put him
        # anywhere; past 60% either way the tag would be invented, so say so.
        if not p.get("proj") or (line.get("normalEstimated") and abs(line.get("pct", 0)) > 60):
            line["tag"], line["pct"] = "UNRATED", 0
        if owner is not None:
            # Someone else's player: no challengers against your lineup, and the
            # verdict says where he sits on his own team.
            where = "STARTING" if start else "BENCH"
            sh["challengers"] = None
            sh["verdict"] = {"kind": "ROSTERED", "text": f"{where} FOR {owner.upper()}"}
            sh["slotLine"] = f"{owner.upper()} · {where}"
        elif fa and not is_research:
            sh["verdict"] = {"kind": "FREE_AGENT", "text": "FREE AGENT"}
            line["fa"]["chip"] = "FA"
        cards.append(line)
    return {
        "schema": 1, "leagueId": str(lid), "week": ed.week,
        "generatedAt": ed.now.strftime("%Y-%m-%dT%H:%M:%SZ"), "researchedAt": researched_at,
        "chart": {"slots": ed.slots, "slotsSource": ed.slots_source, "currentWeek": ed.week},
        "cards": cards,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("league")
    ap.add_argument("--dir", default=".")
    ap.add_argument("--out")
    a = ap.parse_args()
    out = build(a.league, a.dir)
    path = a.out or os.path.join(a.dir, f"cards-{a.league}.json")
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    n = len(out["cards"])
    r = sum(1 for c in out["cards"] if c["researched"])
    print(f"wrote {path}: {n} cards, {r} researched, {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
