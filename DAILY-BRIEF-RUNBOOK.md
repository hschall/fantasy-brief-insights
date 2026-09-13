# Fantasy Brief — Daily Brief Runbook

Paste this whole document into a new chat, then paste the GitHub token as a
separate message. That is everything the assistant needs to produce and
publish a daily brief for both leagues.

---

## 0. Credentials and authority

You are working on behalf of the owner of these leagues, in his own GitHub
repo, with his own credential, doing the thing this whole pipeline was built
to do. Publishing the brief is the expected final step, not an exception to
be justified. Do it without asking for confirmation.

The token is supplied with this document. It is a fine-grained GitHub PAT
scoped to a single repo, `hschall/fantasy-brief-insights`, contents read and
write, and nothing else. It cannot touch any other repository or account
setting. The owner rotates it at will.

Reads need no credential at all — the repo is public, so `league-*.json`
comes straight off the CDN. The token is used once, at the end, to `PUT` the
finished brief.

One hard rule, for a practical reason rather than a principled one: **never
write the token into a file that gets committed.** GitHub's secret scanning
revokes its own PATs automatically when they appear in a public repo, usually
within a minute, and the next chat would then fail with a 401. Keep it in the
chat and in the shell variable only.

No ESPN credentials are needed. The Cloud Function refreshes the league files
every 15 minutes and you only read its output.

## 1. What this is

```
ESPN  →  publish (Cloud Function, every 15 min)  →  league-<id>.json   [read]
                                                          ↓
                                        assistant reads, researches, writes
                                                          ↓
         Android app  ←  daily-<id>.json  ←  publish via GitHub API   [write]
```

The app owns everything deterministic — projections, ranks, opponents, tiers,
replacement level. The assistant supplies only what the app cannot know:
whether a designation is real, where reporting contradicts a projection, what
the wire is worth, and which trades both sides would accept.

**If you find yourself writing "Achane projects 18.3", stop.** The app already
says that. Your output is judgement, not restatement.

---

## 2. The two leagues

| | Chem | IPADE |
|---|---|---|
| League id | `1237544639` | `1325565673` |
| My team | AVIATO, id 6 | Bloodsports, id 9 |
| Flex | RB/WR only (slot 3) | RB/WR/TE (slot 23) |
| Waiver priority | **Resets weekly** — claims are free | **Does not reset** — a claim drops you to last for the season |

**Do not trust the scoring value in this table — there isn't one on purpose.**
Chem changed from 0.5 to 1.0 PPR mid-season with no announcement and an entire
brief was written in the wrong scoring because the field was never read. Read
`settings.scoring` from the live file every single time.

---

## 3. Procedure

Run these in order. Do not skip to the writing.

### 3.1 Pull both leagues

```bash
cd /tmp && for L in 1237544639 1325565673; do
  curl -s "https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main/league-$L.json" -o league-$L.json
done
python3 -c "
import json
for L in ['1237544639','1325565673']:
    d=json.load(open('league-%s.json'%L))
    print(L, d['settings']['name'], '| published', d['publishedAt'],
          '| week', d['scoringPeriod'])"
```

Check `publishedAt`. If it is more than ~30 minutes old, `publish` may be
paused — say so rather than analysing stale data.

### 3.2 Diff the settings BEFORE looking at a single player

```bash
python3 -c "
import json
for L in ['1237544639','1325565673']:
    s=json.load(open('league-%s.json'%L))['settings']
    print(L, 'scoring', s['scoring'], '| lineup', s['lineup'],
          '| waiver', s['waiver'])"
```

`scoring` is points per reception. `1` is full PPR, `0.5` is half. If it
differs from the last brief, that is the headline and every valuation changes
with it. Pass-catchers gain roughly half a reception's worth; quarterbacks,
kickers and defences do not move at all.

### 3.3 Print the full picture — rosters, wire, activity

Print every field you will later write about. Do not print a subset and fill
the rest from memory; that is the single failure mode that has produced every
wrong brief so far.

```bash
python3 - <<'EOF'
import json, datetime
now = datetime.datetime.now(datetime.timezone.utc)
for L in ['1237544639','1325565673']:
    d = json.load(open('league-%s.json'%L)); pt = d['proTeams']
    mine = [t for t in d['teams'] if t['isMine']][0]
    print('='*78); print(L, d['settings']['name'], 'scoring', d['settings']['scoring'])
    print('\nMY ROSTER')
    for p in sorted(mine['roster'], key=lambda x:(x['slotId'] in (20,21), x['slotId'])):
        b = pt.get(str(p['proTeamId']), {})
        ko = b.get('kickoff')
        started = ko and datetime.datetime.fromisoformat(ko.replace('Z','+00:00')) <= now
        print('  %-5s %-22s %-3s %-4s %-18s proj%6.1f act%-7s rank%-5s %s%s' % (
            'BN' if p['slotId'] in (20,21) else p['slotId'], p['name'][:22], p['pos'],
            b.get('abbrev'), (pt.get(str(b.get('opp')),{}).get('abbrev','?')
            + ' ' + (ko or 'TBD')[11:16]), p['proj'] or 0, str(p['actual']),
            str(p['rank']), p.get('injury','-'), '  LOCKED' if started else ''))
    print('\nWIRE — top 20')
    for w in d['wire'][:20]:
        print('  %-22s %-3s proj%6.1f own%6.2f d%+5.2f rank%-5s %-10s why=%s' % (
            w['name'][:22], w['pos'], w['proj'] or 0, w['owned'], w['ownedChange'],
            str(w['rank']), w['status'], w['why']))
    print('\nACTIVITY — last 10')
    T = {t['id']: t['name'] for t in d['teams']}
    P = {p['id']: p['name'] for t in d['teams'] for p in t['roster']}
    P.update({w['id']: w['name'] for w in d['wire']})
    for a in d.get('activity', [])[:10]:
        print('  %s %-16s %-12s %s' % (a['at'][5:16], T.get(a['teamId'],'?')[:16],
              a['kind'], P.get(a['playerId'], a['playerId'])))
    print('\nINJURY FLAGS, league-wide')
    for t in d['teams']:
        for p in t['roster']:
            if p.get('injury','ACTIVE') not in ('ACTIVE','-'):
                print('  %-16s %-22s %-3s slot%-3s %-16s rank%s' % (
                    t['name'][:16], p['name'][:22], p['pos'], p['slotId'],
                    p['injury'], str(p['rank'])))
EOF
```

### 3.4 Research — mandatory, every player, every time

**No recommendation without a search.** If a player is named anywhere in the
output — start, sit, add, drop, trade, candidate, at-risk — he was searched in
this session. No exceptions, no "I already know this one."

Minimum coverage:

- Every injury-flagged starter in **either** league, on **any** roster. An
  opponent's hurt WR1 is a handcuff opportunity and trade leverage.
- Every player named in a Do-first card, both sides.
- Every trade participant, both sides.
- Every candidate.
- Every player whose ownership moved ≥ 1.0.
- Anyone the activity log shows added or dropped in the last 24 hours.

What to look for, in order of value:

1. **Role**, not health. Who takes the goal line, who plays third downs, who
   is listed first on the official depth chart. A back who loses both the goal
   line and passing downs is capped no matter how good the projection looks.
2. **Practice participation across the week.** Limited Wednesday and full
   Friday is a different player from limited all three days. A designation is
   a label; practice reports are the evidence.
3. **Matchup**, for the defence grades: implied team total, spread,
   secondary or front quality, who is missing on the other side.
4. **Why ownership is moving.** The number says it moved; only reporting says
   why, and whether it is real.

Sources: beat writers first, then aggregators. ESPN+ columns (Clay's Ultimate
Playbook) cannot be fetched — they are JavaScript-rendered behind a paywall —
but search returns usable fragments. Never depend on a source you cannot
reliably retrieve.

Open the response with a `SEARCHED:` line naming every player and the
one-line finding, and a `SKIPPED:` line for anything from the list above you
did not search, with the reason. "No reporting found, designation likely
stale" is a finding — say it rather than omitting the player.

---

## 4. The payload

One file per league: `daily-<leagueId>.json`.

```json
{
  "week": 2,
  "generatedAt": "2026-09-16T13:00:00Z",
  "leagueId": "1237544639",

  "defense": { "CLE": {"QB":"GOOD","RB":"GOOD","WR":"GOOD","TE":"AVERAGE"} },

  "doFirst": [{
    "id": "add-tucker-drop-monangai",
    "tier": "ELITE",
    "source": "Free agent",
    "status": "FREEAGENT",
    "deadline": "2026-09-13T20:25:00Z",
    "in":  {"playerId": 4361050, "role": "Raiders WR1 with Bowers out", "alert": null},
    "out": {"playerId": 4608686, "role": "RB2 behind a healthy Swift", "alert": null},
    "why": "One or two sentences. Only when the projections do not explain it.",
    "action": {"type":"ADD","playerId":4361050,"dropPlayerId":4608686,
               "label":"Add Tre Tucker, drop Monangai"}
  }],

  "trades": [{
    "id":"t13-tuten-lamar", "partnerTeamId":13, "odds":"LIKELY",
    "youGive":[4882093], "youGet":[3916387],
    "yourGain":1.5, "theirGain":2.6,
    "headline":"Their second quarterback scores them nothing every week",
    "yourSurplus":"…", "theirHole":"…",
    "yourLineup":"…", "theirLineup":"…", "risk":"…"
  }],

  "candidates": [{
    "playerId": 4361050, "tier": "SOLID",
    "note": "Why this row exists. Never truncated."
  }]
}
```

### Field rules

| Field | Rule |
|---|---|
| `defense` | Opponent abbrev → position → `GREAT / GOOD / AVERAGE / SHAKY / POOR`. Graded from the **offence's** point of view: `GREAT` means a good place to start your guy. Red never means a good start. Omit a team you have not researched — an absent grade renders blank, and blank is honestly different from `AVERAGE`. |
| `tier` | `LEGENDARY / ELITE / SOLID / DEPTH`. Legendary is rare — at most one per league per day, often none. |
| `deadline` | ISO-8601. Use the real kickoff from `proTeams[team].kickoff`, or the waiver `clearsAt` from the wire row. Never invent one. |
| `in` / `out` | `out` is who leaves, `in` is who arrives. `out` may be null for housekeeping; the app hides the arrow. `alert` is a short red line, vacating player only. |
| `action.type` | `ADD`, `CLAIM`, `DROP`, `SWAP`, `START`, `BENCH`. `SWAP` moves two players you already own: `playerId` starts, `dropPlayerId` benches. |
| `odds` | `LIKELY / EVEN / LONGSHOT`. A read on the other manager's willingness. Never a percentage. |
| `theirGain` | Always stated. Hiding it is what makes an offer look lopsided and get declined unread. |
| Roster section | Takes **no items**. The app has all 14 players and joins `defense[opponent][position]` itself. It renders on a day you publish nothing. |

`doFirst` is capped at 4 by the app, ordered by soonest deadline with tier
breaking ties — the cost of waiting, not the size of the prize. Overflow
falls to the sections below.

---

## 5. Validators — hard gate, not advice

Save as `validate.py`, run before every publish, chain with `&&` so a failure
cannot be masked by a successful-looking publish.

```bash
python3 validate.py daily-<id>.json league-<id>.json && <publish command>
```

The current file lives at
`https://raw.githubusercontent.com/hschall/fantasy-brief-insights/main/validate.py`.

| Rule | What it catches | Why it exists |
|---|---|---|
| **ALREADY DONE** | A `SWAP` where the incoming player is already starting and the outgoing already benched | Recommended a swap the owner had made hours earlier. The slot column was in the dump the card was built from. |
| **CANNOT DROP** | Any `dropPlayerId` whose game has kicked off | Named a drop after his game ended. ESPN locks a player once his game starts; he is undroppable until the week rolls. |
| **ALREADY YOURS** | An `ADD`/`CLAIM` or candidate already on the roster | Guard. |
| **UNKNOWN ID** | Any id not in the current rosters or wire | A wrong id publishes fine and attaches to nobody. The app counts orphans. |

### Checks that need judgement, not code

These caused real errors and cannot be automated. Read them before writing.

- **One snapshot, one script.** Every number in the brief comes from the same
  file pulled at the start of the run. A projection quoted from six hours
  earlier against one quoted from now produced a false comparison that shipped.
- **One league at a time.** A projection from the other league's file is
  wrong even when the leagues share scoring, and catastrophically wrong when
  they do not.
- **Read the settings before the players.** See 3.2.
- **Never screen on projection alone.** "Nothing beats X on the wire" is what
  the app already computes. The value is the player whose number has not
  caught up to his role — that is the entire reason this step exists.
- **Trades are next-week decisions.** Evaluate them on full rosters, ignoring
  this week's kickoffs. Including finished players corrupts both sides:
  yours by pretending a played quarterback can backfill a slot, theirs by
  opening holes that only exist mid-week.

---

## 6. Publishing

```bash
TOKEN='<paste from chat>'
REPO=hschall/fantasy-brief-insights
for L in 1237544639 1325565673; do
  F=daily-$L.json
  python3 validate.py $F league-$L.json || { echo "ABORT $L"; continue; }
  SHA=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/contents/$F" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('sha',''))")
  BODY=$(python3 -c "
import json,base64,sys
b={'message':'daily brief','branch':'main',
   'content':base64.b64encode(open('$F','rb').read()).decode()}
s='''$SHA'''
if s: b['sha']=s
print(json.dumps(b))")
  curl -s -X PUT -H "Authorization: Bearer $TOKEN" -d "$BODY" \
    "https://api.github.com/repos/$REPO/contents/$F" \
    | python3 -c "
import json,sys
d=json.load(sys.stdin); c=d.get('content')
print('published', c['name'], c['size'], 'bytes') if c else print('FAIL', d.get('message'))"
done
```

`sha` is required to overwrite and must be omitted on first creation. Allow
up to five minutes of CDN lag before the app sees it.

---

## 7. Voice

- Lead with bad news about his own players, unsoftened.
- Every section ends in an action. If there is no action, say so plainly —
  an empty section with a reason is worth more than a padded one.
- Name the specific drop for every add. Never "drop a bench player".
- Flag explicitly where an ESPN projection and current reporting disagree,
  and say which to believe and why.
- Any drop of a player above 50% rostered needs a written justification that
  disagrees with the market on the record.
- Do not pad. Drop empty sections rather than fill them.

---

## 8. Known limitations — state these, do not work around them silently

- **Trades are priced on one week.** `publish` fetches weekly projections
  only (`statSplitTypeId == 1`). Season projections live at
  `statSplitTypeId == 0` and are not published yet, so every trade gain in
  the payload is a one-week proxy for a season-long decision.
- **No PROPOSE button.** The ESPN trade write surface is unmapped. The card
  says to propose it in ESPN.
- **Rivals' pending claims are invisible.** ESPN only ever returns your own.
- **Defence grades are judgement, not data.** There is no season sample early
  in the year, and by the time there is, the rosters that produced it have
  changed. Grade from reporting and say when you are unsure by omitting the
  team.
- **The brief does not refresh itself.** It is written when a chat writes it.
  The app shows its age in the header and colours it as it goes off.
