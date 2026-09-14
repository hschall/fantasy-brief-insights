"""Checks a daily brief against live league state before it is published.

Every rule here exists because a card shipped wrong. Run it as a gate, chained
with && so a failure cannot be masked by a successful-looking publish:

    python3 validate.py daily-<id>.json league-<id>.json && <publish>

This file is the only copy. An earlier inline duplicate in RUNBOOK.md drifted
from it and carried three rules this one lacked; they are merged in below.
"""
import json, datetime, sys

MAX_DO_FIRST = 4
BENCH = (20, 21)


def validate(brief_path, league_path):
    b = json.load(open(brief_path))
    d = json.load(open(league_path))
    pt = d['proTeams']
    now = datetime.datetime.now(datetime.timezone.utc)
    mine = [t for t in d['teams'] if t['isMine']][0]
    slot = {p['id']: p['slotId'] for p in mine['roster']}
    name = {p['id']: p['name'] for t in d['teams'] for p in t['roster']}
    name.update({w['id']: w['name'] for w in d.get('wire', [])})
    known = set(name)
    errs = []

    def started(pid):
        p = next((x for t in d['teams'] for x in t['roster'] if x['id'] == pid), None) \
            or next((w for w in d.get('wire', []) if w['id'] == pid), None)
        if p is None:
            return False
        ko = pt.get(str(p['proTeamId']), {}).get('kickoff')
        if not ko:
            return False
        return datetime.datetime.fromisoformat(ko.replace('Z', '+00:00')) <= now

    def in_future(iso):
        if not iso:
            return False
        try:
            return datetime.datetime.fromisoformat(iso.replace('Z', '+00:00')) > now
        except ValueError:
            return False

    for c in b.get('doFirst', []):
        cid = c.get('id', '?')
        a = c.get('action') or {}
        kind = a.get('type', '').upper()
        add, drop = a.get('playerId'), a.get('dropPlayerId')

        for pid in (add, drop):
            if pid and pid not in known:
                errs.append(f"{cid}: unknown playerId {pid}")

        # A swap already in effect is not a recommendation.
        if kind == 'SWAP' and add in slot and drop in slot:
            if slot[add] not in BENCH and slot[drop] in BENCH:
                errs.append(f"{cid}: ALREADY DONE \u2014 {name.get(add)} is already "
                            f"starting and {name.get(drop)} is already benched")

        # ESPN locks a player once his game kicks off.
        #
        # But a drop executes when its action executes. For a waiver CLAIM
        # that is clearsAt, by which time the week has rolled and everyone is
        # droppable again \u2014 so the lock only applies to actions taken now.
        # Without this carve-out, every Sunday-evening claim fails the gate.
        executes_later = kind == 'CLAIM' and in_future(c.get('deadline'))
        if drop and started(drop) and not executes_later:
            errs.append(f"{cid}: CANNOT DROP {name.get(drop)} \u2014 his game has started")

        if kind in ('ADD', 'CLAIM') and add in slot:
            errs.append(f"{cid}: {name.get(add)} is already on your roster")

        if not c.get('deadline'):
            errs.append(f"{cid}: no deadline")

    if len(b.get('doFirst', [])) > MAX_DO_FIRST:
        errs.append(f"doFirst has more than {MAX_DO_FIRST} cards")

    for c in b.get('candidates', []):
        pid = c.get('playerId')
        if pid not in known:
            errs.append(f"candidate: unknown playerId {pid}")
        if pid in slot:
            errs.append(f"candidate {name.get(pid)} is already on your roster")

    for t in b.get('trades', []):
        for pid in t.get('youGive', []) + t.get('youGet', []):
            if pid not in known:
                errs.append(f"trade {t.get('id')}: unknown playerId {pid}")
        # A proposal that helps only one side is a wish, not a proposal.
        if t.get('theirGain', 0) <= 0:
            errs.append(f"trade {t.get('id')}: the other side does not gain")

    return errs


if __name__ == '__main__':
    e = validate(sys.argv[1], sys.argv[2])
    print('\n'.join('  FAIL ' + x for x in e) if e else '  all checks pass')
    sys.exit(1 if e else 0)
