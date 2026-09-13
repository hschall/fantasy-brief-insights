"""Checks a daily brief against live league state before it is published.

Every rule here exists because a card shipped wrong: recommending a swap the
owner had already made, and naming a drop whose game had started.
"""
import json, datetime, sys

def validate(brief_path, league_path):
    b = json.load(open(brief_path)); d = json.load(open(league_path))
    pt = d['proTeams']; now = datetime.datetime.now(datetime.timezone.utc)
    mine = [t for t in d['teams'] if t['isMine']][0]
    slot = {p['playerId'] if 'playerId' in p else p['id']: p['slotId'] for p in mine['roster']}
    name = {p['id']: p['name'] for t in d['teams'] for p in t['roster']}
    name.update({w['id']: w['name'] for w in d['wire']})
    known = set(name)
    BENCH = (20, 21)

    def started(pid):
        p = next((x for t in d['teams'] for x in t['roster'] if x['id'] == pid), None)
        if p is None:
            p = next((w for w in d['wire'] if w['id'] == pid), None)
        if p is None: return False
        ko = pt.get(str(p['proTeamId']), {}).get('kickoff')
        if not ko: return False
        return datetime.datetime.fromisoformat(ko.replace('Z', '+00:00')) <= now

    errs = []
    for c in b.get('doFirst', []):
        cid = c.get('id', '?')
        a = c.get('action') or {}
        add, drop = a.get('playerId'), a.get('dropPlayerId')
        for pid in (add, drop):
            if pid and pid not in known:
                errs.append(f"{cid}: unknown playerId {pid}")
        # Rule 1: a swap already in effect is not a recommendation.
        if a.get('type', '').upper() == 'SWAP' and add in slot and drop in slot:
            if slot[add] not in BENCH and slot[drop] in BENCH:
                errs.append(f"{cid}: ALREADY DONE — {name.get(add)} is already starting "
                            f"and {name.get(drop)} is already benched")
        # Rule 2: ESPN locks a player once his game kicks off.
        if drop and started(drop):
            errs.append(f"{cid}: CANNOT DROP {name.get(drop)} — his game has started")
        # Rule 3: adding someone already rostered by me.
        if a.get('type', '').upper() in ('ADD', 'CLAIM') and add in slot:
            errs.append(f"{cid}: {name.get(add)} is already on your roster")
    for c in b.get('candidates', []):
        if c['playerId'] in slot:
            errs.append(f"candidate {name.get(c['playerId'])} is already on your roster")
    return errs

if __name__ == '__main__':
    e = validate(sys.argv[1], sys.argv[2])
    print('\n'.join('  FAIL ' + x for x in e) if e else '  all checks pass')
    sys.exit(1 if e else 0)
