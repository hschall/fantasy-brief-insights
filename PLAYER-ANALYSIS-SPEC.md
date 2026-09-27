# Player analysis — design spec

**Version 2**, 2026-09-26. Version 1 proposed a reliability score, bias
correction, a volume override and a game-state rule. The 2025 backtest
(`BACKTEST-2025.md`) rejected all four. This version keeps what survived.

**Who owns what.** STRATEGY.md owns policy — what the lineup should be and
why. This file owns mechanics — the tools, the schema, the checks. RUNBOOK.md
owns process — the order a run does things in. Where they overlap, STRATEGY
wins and the others point to it.

---

## The approach

**ESPN's projection sets the lineup; the edge is information ESPN has not
priced yet.**

On statistics alone nothing beat the projection: 65% on start/sit across
2025, and every adjustment tried made it worse. So we stop trying to
out-calculate it and compete where a projection cannot see — injuries,
inactives, quarterback and role changes that break after it was set.

That makes two things central: **researching every player on every run**, and
**a contingency for anyone whose status is in doubt**.

---

## Part 1 — The player report

### Five questions, every player, every run

| Question | From | |
|---|---|---|
| **Availability** | league file + injury report | His designation and practice trend |
| **Role** | week archive + research | His share of team volume, and whether it is changing — description, not a score |
| **Competition** | archive + league file + research | Who shares that volume, their status, what their absence frees |
| **Passer** | archive + research | For anyone who catches passes: who is throwing, and whether that is in doubt |
| **Baseline** | `lineup_check.py` | Whether the projection lineup starts him |

Kickers and defences answer availability only.

Shown alongside, **never used to decide**: ESPN's expert weekly rank and its
range — *"WR8 this week, experts WR5–WR14"*. It is being measured (see
Part 4).

### Research rules

- **Every rostered player, bench included, every run.** No exceptions.
- **The unit is the team situation.** One search on a team's injury report
  answers everyone on that team and who is throwing. About thirteen teams per
  roster: **30–45 searches** a run across both leagues.
- **Minimum per team:** the latest injury report, and role or depth-chart
  news from the last 48 hours.
- **Every flag from `player_report.py` is answered by name.**
- **"No change" is a complete answer.** Record what was checked and when. A
  search that finds nothing must not turn into a narrative.
- **Timing matters more than volume.** Note when news broke. If it broke
  before ESPN's projection updated, it is already priced.
- If a run runs short, name what was thinner in `cannotSee`. Never drop a
  player silently.

### Flags

Computed by `player_report.py` from data. The tool never writes a verdict.

| Flag | Fires when | Research asks |
|---|---|---|
| `OWN_QUESTIONABLE` | His designation is Q | Practice trend; inactives 90 min before kickoff |
| `OWN_OUT` | Out, doubtful, IR, suspended | Confirm; who replaces him in the lineup |
| `VACATED` | A teammate with ≥15% of the volume is out | Confirm inactive; who inherits it; did ESPN already adjust? |
| `RIVAL_QUESTIONABLE` | A teammate with ≥15% of the volume is Q | If he sits, this player's role grows |
| `ROLE_UP` / `ROLE_DOWN` | Share moved ≥10 points and his own volume moved the same way | Why — injury, depth chart, or noise |
| `PASSER_OUT` / `PASSER_QUESTIONABLE` | The quarterback throwing to him is out or Q | Who replaces him; is it a downgrade |
| `QB_CHANGE` | A different quarterback led in different weeks | Who starts now |

A week with no carries, targets or pass attempts is an **absence**, not zero
volume. A falling share only counts if his own volume did not rise.

### Report schema

One `report` on every roster row:

```json
"report": {
  "flags": ["OWN_QUESTIONABLE", "VACATED"],
  "availability": "QUESTIONABLE — limited Wed, full Thu and Fri",
  "role": "55% of Pittsburgh's carries, 4–6 targets; steady",
  "competition": "Dowdle OUT — ~7 carries and ~2 targets a week vacated",
  "passer": "Aaron Rodgers, active",
  "checked": "Steelers injury report Fri 25 Sep; team news",
  "verdict": "CONDITIONAL",
  "trigger": "Active at inactives, Sun 11:30 CDMX",
  "fallback": "Bhayshul Tuten",
  "summary": "Must-start if active: lead back with Dowdle's volume added."
}
```

- `verdict` is **START**, **BENCH** or **CONDITIONAL**.
- **CONDITIONAL** needs `trigger` and `fallback`, and a matching contingency
  in `lineupCheck`.
- Kicker and defence rows need `availability`, `checked`, `verdict` and
  `summary` only.

---

## Part 2 — Why there is no reliability score

Version 1 proposed one: floor rate, usage stability, bias and volatility,
shrunk toward position norms. Measured on 2025:

- **Floor rate and bias do not persist** from one half-season to the next
  (0.02 and 0.03). Beating a projection is luck, not a trait.
- **The proposed score was backwards**: its top third held its floor slightly
  *worse* than its bottom third.
- **Volatile players do not boom more** than steady ones at the same
  projection.
- **Bias correction made start/sit worse** at every setting.

ESPN's projection already absorbs what a reliability score would capture.
Full results in `BACKTEST-2025.md`.

---

## Part 3 — The lineup

Policy lives in STRATEGY.md section 1. Mechanically:

1. **Start from the projection lineup** — `lineup_check.py`.
2. **Close calls** (within 2 projected points) go to the projection. No
   thesis.
3. **A move away from the projection needs news ESPN has not priced**, named,
   with when it broke: an out or inactive player, a late quarterback change,
   a role change reported after projections were set.
4. **Never on last week's points.**
5. **Every CONDITIONAL starter gets a contingency** — the trigger, when to
   check it, and who replaces him. `lineup_check.py` proposes the fallback:
   the best-projected bench player eligible for that slot.

### Schema — extends `lineupCheck`

```json
"lineupCheck": {
  "asSet": 126.8,
  "best": 126.8,
  "gap": 0.0,
  "verdict": "The lineup as set is the projection lineup; Warren is conditional.",
  "moves": [
    {"start": "Bhayshul Tuten", "over": "Terry McLaurin",
     "reason": "McLaurin ruled out Saturday 18:00, after projections set"}
  ],
  "contingencies": [
    {"player": "Jaylen Warren",
     "if": "Inactive at 11:30 Sunday",
     "then": "Start Bhayshul Tuten in his RB slot"}
  ]
}
```

`moves` is usually empty. Each entry names the news and when it broke.

---

## Part 4 — Measured, not yet used

**ESPN's expert weekly rank.** `publish` freezes each player's `rank`,
`rankHigh` and `rankLow` at his game's kickoff and stores them with the week
archive. Once five or six weeks exist, the backtest's pairwise test runs on
it: projection alone, expert rank alone, both — and whether a wide expert
range predicts a boom-or-bust week. It earns a place only if it wins.

---

## Enforcement

**Validator:**

- Every roster row has a `report`. Skill players: `flags`, `availability`,
  `role`, `competition`, `passer`, `checked`, `verdict`, `summary`. Kicker and
  defence: `availability`, `checked`, `verdict`, `summary`.
- `verdict` is START, BENCH or CONDITIONAL.
- CONDITIONAL has `trigger` and `fallback`, and a contingency naming him.
- Every `moves` entry has a `reason`.

**Weekly review** (STRATEGY section 11): points left on the bench, how many
came from our own overrides — target zero — whether each contingency resolved
as its trigger said, and search counts against the budget.

---

## Tools

| Tool | Does |
|---|---|
| `lineup_check.py` | Projection lineup vs as set; close calls; contingencies for questionable starters, with fallbacks |
| `player_report.py` | The five questions from data, flags, and what to research |
| `waiver_analysis.py` | Wire against roster on usage; questions, not answers |

All read-only. All run in Step 3.5, before any research.
