#!/usr/bin/env python3
"""Gate for edition-<league>.json — the Insights paper's payload.

The app renders this file and computes nothing, so anything wrong here is
wrong on the phone. Run it after edition.py; publish only a file that passes.

Usage:  python3 validate_edition.py edition-<L>.json league-<L>.json
"""
import json
import sys

TAGS = {"ELITE", "FAVORABLE", "NEUTRAL", "TOUGH", "AVOID"}
VERDICTS = {"START", "IF_ACTIVE", "BENCH", "WATCH", "ADD"}
URGENCY = {"today", "week", "ahead"}
ACTIONS = {"swap", "add", "tab", "sheet", "byes"}
TABS = {"matchup", "roster", "wire"}


def main(ep, lp):
    e, lg = json.load(open(ep)), json.load(open(lp))
    errs, warns = [], []

    if e.get("schema") != 1:
        errs.append(f"schema is {e.get('schema')}, the app understands 1")
    for k in ("edition", "chart", "front", "doFirst", "roster", "wire", "notes"):
        if k not in e:
            errs.append(f"missing section '{k}'")
    if errs:
        return report(errs, warns)
    if e["edition"].get("dryRun"):
        warns.append("dryRun is true — fine for testing, never for a scheduled publish")
    if str(e["edition"].get("leagueId")) != str(lg.get("leagueId")):
        errs.append("edition and league file are for different leagues")

    # The app must never leave itself: no links anywhere in the payload.
    blob = json.dumps(e)
    if "http://" in blob or "https://" in blob:
        errs.append("the edition contains a URL — every button must act inside the app")

    ch = e["chart"]
    if ch.get("slots", 0) < ch.get("currentWeek", 99):
        errs.append("chart.slots is smaller than the current week")

    mine = next(t for t in lg["teams"] if t["isMine"])
    roster_ids = {p["id"] for p in mine["roster"]}
    wire_ids = {w["id"] for w in lg.get("wire", [])}
    lines = e["roster"]["lines"]
    if {l["playerId"] for l in lines} != roster_ids:
        errs.append(f"roster lines ({len(lines)}) don't match the league file's roster ({len(roster_ids)})")

    cards = e["doFirst"]["cards"]
    contingency_for = {c.get("playerId") for c in cards
                       if (c.get("primary") or {}).get("action", {}).get("type") == "swap"}
    for l in lines + e["wire"]["candidates"]:
        who = l.get("name", "?")
        sh = l.get("sheet", {})
        if l.get("tag") not in TAGS:
            errs.append(f"{who}: tag {l.get('tag')!r}")
        for k in ("reason", "slotLine"):
            if not str(l.get(k, "")).strip():
                errs.append(f"{who}: no {k}")
        v = (sh.get("verdict") or {}).get("kind")
        if v not in VERDICTS:
            errs.append(f"{who}: verdict {v!r}")
        for k in ("deck", "logic"):
            if not str(sh.get(k, "")).strip():
                errs.append(f"{who}: sheet has no {k}")
        if not sh.get("table"):
            errs.append(f"{who}: empty spec table")
        if len(sh.get("weeks", [])) != ch["currentWeek"] - 1:
            errs.append(f"{who}: {len(sh.get('weeks', []))} weeks, expected {ch['currentWeek'] - 1}")
        if v == "IF_ACTIVE" and l["playerId"] not in contingency_for:
            errs.append(f"{who}: START IF ACTIVE but no Do-first card swaps him out")

    for w in e["wire"]["candidates"]:
        if not w.get("fa"):
            errs.append(f"{w.get('name')}: wire candidate without ownership")
        if w.get("playerId") in roster_ids:
            errs.append(f"{w.get('name')}: already on the roster")
        if w["sheet"]["verdict"]["kind"] == "ADD" and not any(r["label"] == "THE DROP" for r in w["sheet"]["table"]):
            errs.append(f"{w.get('name')}: ADD with no drop named")

    dues = [c.get("due", "") for c in cards]
    if dues != sorted(dues):
        errs.append("Do-first cards are not in deadline order")
    sheet_ids = {l["playerId"] for l in lines + e["wire"]["candidates"]}
    for c in cards:
        cid = c.get("id", "?")
        if c.get("urgency") not in URGENCY:
            errs.append(f"card {cid}: urgency {c.get('urgency')!r}")
        for side in ("primary", "secondary"):
            b = c.get(side)
            if not b:
                continue
            a = b.get("action", {})
            t = a.get("type")
            if t not in ACTIONS:
                errs.append(f"card {cid}: {side} action {t!r}")
            elif t == "swap" and not (a.get("start") in roster_ids and a.get("bench") in roster_ids):
                errs.append(f"card {cid}: swap names a player not on the roster")
            elif t == "add" and a.get("playerId") not in wire_ids | sheet_ids:
                errs.append(f"card {cid}: add names a player not on the wire")
            elif t == "tab" and a.get("tab") not in TABS:
                errs.append(f"card {cid}: tab {a.get('tab')!r}")
            elif t == "sheet" and a.get("playerId") not in sheet_ids:
                errs.append(f"card {cid}: opens a sheet the edition doesn't have")

    scr = e["wire"].get("screen")
    if not scr or not scr.get("rows"):
        errs.append("wire.screen is missing or empty — the screen runs every edition")
    else:
        for r in scr["rows"]:
            if r.get("verdict") not in ("CLAIM", "CLAIM_IF_SPACE", "WATCH", "PASS") or not str(r.get("reason", "")).strip():
                errs.append(f"screen: {r.get('name')} has no verdict with a reason")
            if r.get("verdict") == "CLAIM" and not r.get("drop"):
                errs.append(f"screen: {r.get('name')} is a CLAIM with no drop named")
    if not e["notes"].get("cannotSee"):
        errs.append("notes.cannotSee is empty — say what this edition could not confirm")
    lead = e["front"]["lead"]
    for k in ("headline", "deck", "body"):
        if not str(lead.get(k, "")).strip():
            errs.append(f"lead has no {k}")
    return report(errs, warns)


def report(errs, warns):
    for w in warns:
        print("  warn", w)
    for x in errs:
        print("  FAIL", x)
    if not errs:
        print("  all checks pass")
    return 1 if errs else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: validate_edition.py edition-<L>.json league-<L>.json")
    sys.exit(main(sys.argv[1], sys.argv[2]))
