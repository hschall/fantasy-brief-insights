"""Checks a daily brief against live league state before it is published.

Every rule here exists because a card shipped wrong. Run it as a gate, chained
with && so a failure cannot be masked by a successful-looking publish:

    python3 validate.py daily-<id>.json league-<id>.json && <publish>

This file is the only copy. An inline duplicate in RUNBOOK.md drifted from it
once and carried different rules, which is how a gate stops being a gate.
"""
import datetime
import json
import sys

MAX_DO_FIRST = 4
BENCH = (20, 21)
GRADES = {"GREAT", "GOOD", "AVERAGE", "SHAKY", "POOR", "SIT"}


def validate(brief_path, league_path):
    b = json.load(open(brief_path))
    d = json.load(open(league_path))
    pt = d["proTeams"]
    now = datetime.datetime.now(datetime.timezone.utc)
    mine = [t for t in d["teams"] if t["isMine"]][0]
    slot = {p["id"]: p["slotId"] for p in mine["roster"]}
    name = {p["id"]: p["name"] for t in d["teams"] for p in t["roster"]}
    name.update({w["id"]: w["name"] for w in d.get("wire", [])})
    known = set(name)
    errs = []

    def started(pid):
        p = next((x for t in d["teams"] for x in t["roster"] if x["id"] == pid), None) \
            or next((w for w in d.get("wire", []) if w["id"] == pid), None)
        if p is None:
            return False
        ko = pt.get(str(p["proTeamId"]), {}).get("kickoff")
        if not ko:
            return False
        return datetime.datetime.fromisoformat(ko.replace("Z", "+00:00")) <= now

    def in_future(iso):
        if not iso:
            return False
        try:
            return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")) > now
        except ValueError:
            return False

    # The condition that would prove the call wrong, whatever it is called.
    # A prediction gets "Falsified if"; a watch row gets "What would change
    # it" or "When to act", which is the same idea wearing the label that
    # fits the context. What is not acceptable is having none of them.
    CONDITION = ("falsif", "would change", "when to act", "changes it")

    def case_ok(node, where):
        """Labelled blocks, one of which names what would prove it wrong."""
        case = node.get("case") or []
        if not case:
            errs.append(f"{where}: no case")
            return
        for blk in case:
            if not blk.get("label"):
                errs.append(f"{where}: a case block has no label")
            if not blk.get("body"):
                errs.append(f"{where}: a case block has no body")
        labels = " ".join(str(x.get("label", "")).lower() for x in case)
        if not any(k in labels for k in CONDITION):
            errs.append(f"{where}: no block saying what would prove this wrong")

    # ---- header ------------------------------------------------------
    for f in ("leagueName", "teamName", "generatedAt"):
        if not b.get(f):
            errs.append(f"header: missing {f}")

    # ---- do first ----------------------------------------------------
    cards = b.get("doFirst", [])
    if len(cards) > MAX_DO_FIRST:
        errs.append(f"doFirst has more than {MAX_DO_FIRST} cards")
    for c in cards:
        cid = c.get("id", "?")
        if not c.get("title"):
            errs.append(f"{cid}: no title")
        if not c.get("deadline"):
            errs.append(f"{cid}: no deadline")
        case_ok(c, cid)

        a = c.get("action") or {}
        kind = str(a.get("type", "")).upper()
        add, drop = a.get("playerId"), a.get("dropPlayerId")
        for pid in (add, drop):
            if pid and pid not in known:
                errs.append(f"{cid}: unknown playerId {pid}")

        # A swap already in effect is not a recommendation.
        if kind == "SWAP" and add in slot and drop in slot:
            if slot[add] not in BENCH and slot[drop] in BENCH:
                errs.append(f"{cid}: ALREADY DONE - {name.get(add)} is already "
                            f"starting and {name.get(drop)} is already benched")

        # A drop executes when its action executes. For a waiver CLAIM that is
        # clearsAt, and for WHEN_UNLOCKED it is next week — by then the roster
        # has unlocked and everyone is droppable again.
        unlocked = str(c.get("deadline", "")).upper() == "WHEN_UNLOCKED"
        later = unlocked or (kind == "CLAIM" and in_future(c.get("deadline")))
        if drop and started(drop) and not later:
            errs.append(f"{cid}: CANNOT DROP {name.get(drop)} - his game has started")

        if kind in ("ADD", "CLAIM") and add in slot:
            errs.append(f"{cid}: {name.get(add)} is already on your roster")

        if unlocked and str(c.get("tier", "")).upper() in ("LEGENDARY", "ELITE"):
            errs.append(f"{cid}: WHEN_UNLOCKED cards cannot be LEGENDARY or ELITE")

    # ---- what went wrong ---------------------------------------------
    for n in b.get("whatWentWrong", []):
        case_ok(n, f"wentWrong {n.get('id', n.get('title', '?'))}")

    # ---- roster ------------------------------------------------------
    r = b.get("roster")
    if not r:
        errs.append("no roster block - the app renders nothing without it")
    else:
        state = str(r.get("state", "")).upper()
        if state not in ("THURSDAY", "SUNDAY", "FINAL"):
            errs.append(f"roster: bad state {state!r}")
        rows = (r.get("starting") or []) + (r.get("bench") or [])
        if len(rows) != len(mine["roster"]):
            errs.append(f"roster: {len(rows)} rows, league file has "
                        f"{len(mine['roster'])}")
        for row in rows:
            who = row.get("name", "?")
            if row.get("playerId") and row["playerId"] not in known:
                errs.append(f"roster {who}: unknown playerId")
            g = row.get("grade")
            if g and str(g).upper() not in GRADES:
                errs.append(f"roster {who}: bad grade {g!r}")
            if state == "FINAL" and row.get("actual") is None:
                errs.append(f"roster {who}: FINAL but no actual")
            if state == "THURSDAY" and not g and not row.get("notResearched"):
                errs.append(f"roster {who}: no grade and not flagged "
                            f"notResearched - guess or flag, never blank")

    # ---- trades ------------------------------------------------------
    for t in b.get("trades", []):
        tid = t.get("id", "?")
        for side in ("youGive", "youGet"):
            if not t.get(side):
                errs.append(f"trade {tid}: empty {side}")
            for p in t.get(side, []):
                if p.get("playerId") and p["playerId"] not in known:
                    errs.append(f"trade {tid}: unknown playerId {p['playerId']}")
        if not t.get("theirGainLine"):
            errs.append(f"trade {tid}: their gain is not stated")
        case_ok(t, f"trade {tid}")

    # ---- watch lists -------------------------------------------------
    for key in ("worthALook", "doNotChase"):
        for w in b.get(key, []):
            who = w.get("name", "?")
            if w.get("playerId") and w["playerId"] in slot:
                errs.append(f"{key} {who}: already on your roster")
            case_ok(w, f"{key} {who}")

    # ---- limits ------------------------------------------------------
    if not b.get("cannotSee"):
        errs.append("cannotSee is empty - every run has limits worth stating")

    return errs


if __name__ == "__main__":
    e = validate(sys.argv[1], sys.argv[2])
    print("\n".join("  FAIL " + x for x in e) if e else "  all checks pass")
    sys.exit(1 if e else 0)
