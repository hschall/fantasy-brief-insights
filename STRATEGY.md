# Strategy

Standing policy for both leagues. Read this every run, before writing any
recommendation. It outranks the per-run optimisation: if a move the numbers
like conflicts with something here, the numbers lose, or the card explains
why this is the exception.

Last reviewed: 2026-09-26, after a backtest on the full 2025 season
(`BACKTEST-2025.md`). Section 0 records what the 0-4 start taught; section 1
is rewritten from what the backtest proved. Mechanics are in
`PLAYER-ANALYSIS-SPEC.md`; this document owns the policy, and wins where they
differ.

---

## 0. What the first two weeks proved

Both leagues are 0-2. Every one of the four losses was **winnable with the
roster already owned**:

| | Started | Best available | Left on bench | Lost by |
|---|---|---|---|---|
| Chem W1 | 125.1 | 169.9 | 44.8 | 11.4 |
| Chem W2 | 110.3 | 123.6 | 13.3 | 12.5 |
| IPADE W1 | 95.8 | 133.0 | 37.2 | 15.4 |
| IPADE W2 | 87.5 | 126.2 | 38.7 | 9.9 |

134 points left on benches; every margin under 16. The games were lost in
the lineup, not on the wire.

Of those points, **43.5 came from lineups a brief actively recommended** and
defended. The single largest failure was the IPADE quarterback slot:

- Week 1: started Stafford 4.1, benched Lawrence 25.6
- Week 2: started Lawrence 5.2 — *because of week 1* — benched Stafford 26.5

42.8 points from one slot by switching to whoever scored last week. In week
2, starting purely by projection would have **won** the game. It was the
only time the brief overrode projection, and it cost a win.

Decision-log outcomes split sharply by type: holds were right 67% of the
time, adds 17%, lineup calls 33%, trades and claims 0%. **The system is good
at declining and bad at acting.**

Three things follow, and the rest of this document is built on them:

1. **The lineup is the highest-value decision in the week and got the least
   attention.** It is free, it is certain in effect, and it decided all four
   games.
2. **Two weeks of data cannot tell a slump from a role change.** Mechanical
   rules on this sample produce confident nonsense in both directions —
   they flag a stud through one bad game, and they protect a player whose
   role has genuinely collapsed.
3. **Acting has a cost that deciding does not.** Most moves made were
   neutral-to-negative. The default is to do nothing.

### What the backtest added

Tested on every startable player-week of 2025, **nothing statistical beat
ESPN's weekly projection** at picking the better of two players: 65.2% on
start/sit. Adding recent volume made it worse at every weight. So did
correcting it by a player's past bias. Whether a player beats his projection
does not persist from one half-season to the next — it is luck, not a trait.

An earlier version of this document cited Coker's 9 opportunities against
McLaurin's 4 as proof that volume beats projection. It was one example.
Across 43,632 comparisons it does not.

---

## 1. Lineup policy — first, because it is where games are lost

### The rule

**ESPN's projection sets the lineup.** It is the best estimate available —
nothing tested beat it — so the lineup is the projection lineup unless there
is news it has not priced.

**Close calls go to the projection, with no thesis.** Within two projected
points, even ESPN is right only 56% of the time. That is a coin flip, and
analysis spent on it is confident reasoning about noise.

**The edge is information, not arithmetic.** A projection cannot know who is
inactive at 11:30 on Sunday, or that a quarterback was ruled out after it was
set. That is where research earns its keep — which is why every player is
researched on every run (below).

### Questionable starters get a contingency

A starter whose status is in doubt stays in the lineup with a named fallback
and a time to check: *"Start Warren; if he is inactive at 11:30 Sunday, start
Tuten."* The decision is made in advance, so Sunday morning is a checklist.

### Every player is researched, every run

Bench included, no exceptions. The unit is the team situation — one injury
report covers everyone on that team and who is throwing. "No change, checked
Friday's report" is a complete answer; a search that finds nothing must not
become a narrative.

### What never justifies overriding projection

**Last week's points.** This is the rule that cost the most and it is not
negotiable. A player who scored well is not thereby better; a player who
scored badly is not thereby worse. If the override rests on a box score
rather than a role, it does not happen.

**A volume gap.** ESPN's projection already contains usage; adding it again
made decisions worse in the backtest. Use volume to understand a player,
never to overrule his projection.

**Last season.** A player's 2025 numbers describe a team, a quarterback and a
body that may no longer exist. They are context, never evidence for a start
or an add.

The only legitimate overrides are **news ESPN has not priced**, with a name
and a time attached: an inactive or ruled-out player, a quarterback change or
a depth-chart move reported after the projection was set. The card states
which, and when it broke. If the news came before the projection updated, it
is already in the number.

There is no separate rule protecting studs. The backtest found ESPN rates its
top-12 players as fairly as everyone else (0.95 of projection, against 0.97
for the next tier), so the projection already protects them — and the rule
against overriding on last week's points is what stops a stud being benched
on one bad game.

### Two strikes

A lineup thesis that loses **twice** closes automatically and the slot
reopens. `chem-flex-mclaurin-over-coker` was held through three consecutive
losing weeks. A falsification condition that survives three losses is not a
condition.

---

## 2. Both leagues are 10-team full PPR

**Shallow means stars matter and depth does not.** Every roster looks solid,
so the edge comes from difference-makers. Flex-level players are close to
worthless as assets because the wire replaces mid-level production. A
two-for-one trade almost always favours the side receiving the single best
player — and that side should usually be me.

**Full PPR pays for volume.** A reception is a point. So:

- A target is worth more than a carry. Weigh them accordingly.
- Target share and route participation are the floor; touchdowns are noise.
- A committee back who loses the goal line *and* third downs collects the
  least valuable touches available.
- These describe why a projection is what it is. They are not a reason to
  overrule it — see section 1.

**Volume without production is a warning, not a signal.** Allgeier drew 13
opportunities a game for 6.5 points, with a role falling from 19 to 7. High
volume that produces nothing is usually low-value touches or a shrinking
role, and a naïve volume screen walks straight into it.

---

## 3. The waiver wire

### The default is nothing

In a 10-team league the wire is deep and most adds are marginal. **"Nothing
worth adding" is the expected answer most weeks**, and the brief says it
plainly rather than manufacturing a card to fill the section.

### The data finds the question; research answers it

The waiver analysis section compares the wire against my roster on data:
opportunity, production, and trend. But two weeks of data cannot
distinguish a slump from a role change, so the data's job is to **surface
disagreements**, not to decide them:

- **Rising on the wire:** growing opportunity, real production, role not
  shrinking. The question is *why* — and only research answers it.
- **Falling on my roster:** a player whose usage has collapsed while his
  ranking has not caught up. *Loveland at 0.7 points a game on 4
  opportunities while ESPN still ranks him top-24 is this case.*

A move needs both: the data showing a gap, **and** a named role reason
explaining it. Data alone is the chasing pattern. Research alone is the
narrative pattern. Together they are a decision.

### The bar

A wire player is worth adding only if he beats my weakest **movable**
player — never one inside this week's top 24 at his position by ESPN's expert
rank — on:

- opportunity in the most recent week, by a real margin (~30%)
- a trend that is flat or rising, never falling
- production that is at least comparable, not volume that scores nothing

and there is a role reason that makes the gap likely to persist.

### The churn rule

This exists because of real failures, and it has been violated since it was
written: the Vikings D/ST were claimed at 07:15 and dropped at 14:37 the
same day; the IPADE tight-end seat changed hands three times in four days.

**Never churn the same slot twice in a week.** If a roster spot changed
hands in the last 7 days it is closed, unless the player in it is ruled out.

Ask not *"is this player the best use of the slot?"* — that re-litigates the
roster every run and flips on noise. Ask:

> **Has the reason I added him changed?**

If the thesis is intact, he stays, even if someone now projects 0.4 higher.

---

## 4. Waiver priority

**Chem — priority resets weekly.** It is not a resource, but claims still
have an ordering cost: four claims in one morning pushed AVIATO to last and
lost the Purdy claim that mattered. Claim what is worth claiming, and order
the claims by value, not by when they were thought of.

**IPADE — priority never resets.** One claim drops me to last for the rest
of the season.

- **Free agents are free.** Only waiver claims consume priority. Check
  `status` before treating an add as expensive.
- Spend priority only on a player who would start immediately, or a genuine
  league-winner. Never on a patch, a handcuff or a streamer.

---

## 5. Kicker and defence

Streamed on matchup, but **check the incumbent first** and report the
comparison either way. A stream that gains 0.3 points costs a roster move
for nothing. Decision-log outcomes on streaming moves have been poor; the
bar is a real gap, not a marginal one.

---

## 6. Trades

**Only propose what both sides gain,** by their own starting lineups, and
state their gain in the headline.

**Consolidate.** Two starters for one better starter is the right direction
in a shallow league.

**Trade into the other side's urgency.** Structural surplus, not name value.

**Buy low on usage, sell high on efficiency.**

**Untouchable:** any player top-5 at his position.

**Check the other team's slot column before naming anyone** — a proposal
that asks for their starter has been drafted twice.

No trade has closed RIGHT yet. The prime window is weeks 9-11; there is no
urgency before then.

---

## 7. The calendar

| When | What changes |
|---|---|
| Weeks 1-3 | Sample is noise. React to **role** news only, never to box scores. Two weeks of data is two data points. |
| Weeks 4-6 | Roles have settled. Volume trends become readable. First real window to buy low on a slow starter with good usage. |
| Week 7 | Jacksonville bye in IPADE. Solve it in week 7 with what the wire offers then, never by spending priority early. |
| Weeks 9-11 | Prime trade window. |
| Week 12 | Evaluate every asset against weeks 15-17. |
| 12/2 | Trade deadline, both leagues. |
| Weeks 15-17 | Playoffs. Start studs regardless of matchup. |

---

## 8. The two leagues are different problems

**Chem scores 5th of 10** both weeks — above median, losing to top-four
scores. The roster is competitive. The fix is lineup discipline.

**IPADE scores 8th and 9th of 10** — a 25-point gap to the week-2 median
that no lineup rule closes. That is a roster problem, and it needs a
separate look at whether the roster lacks talent or is badly constructed.
Lineup discipline is necessary there but not sufficient.

**Chem** — full PPR, flex RB/WR only, priority resets weekly. A second tight
end has no route into the lineup; do not carry one.
**IPADE** — full PPR, flex RB/WR **and TE**, priority never resets. A second
startable tight end has real value.

---

## 9. Honest grading

The decision log is only evidence if it grades itself honestly.

- **If the falsification condition fired, the entry closes WRONG.** Not
  EXPIRED. The Lawrence recommendation cost 21.3 points and closed EXPIRED
  on *"Stafford could outscore that"* — he did, by 21.3.
- **EXPIRED is for decisions that were never tested**, not for ones that
  failed quietly.
- **Do not record declines to stop future runs rediscovering things.** If a
  run keeps resurfacing the same non-move, that is a sign the analysis is
  re-deriving from scratch — fix the analysis, do not paper over it with a
  growing list of standing refusals.
- **The week archive is ground truth.** Every result is checked against
  `week-<league>-<n>.json`, never an article. A grade once closed RIGHT on
  "about 15.8 to 14.3" from news; the archive said 9.8 to 14.2.
- **A lineup call is graded on its outcome.** "Start A over B" is RIGHT only
  if A outscored B, whatever its written falsifier said.

---

## 10. What I do not want

- **Do not chase last week's points.** It is the most expensive mistake this
  system has made.
- **Do not manufacture cards.** An empty section is a legitimate answer.
- **Do not write a thesis on a coin flip.**
- **Do not bench a stud on one bad week.**
- **Do not let volume overrule a projection.**
- **Do not reintroduce an idea the backtest rejected** — a reliability score,
  bias correction, a volume override, a favoured/underdog rule — without new
  evidence that beats ESPN's projection.
- **Do not churn a slot twice in a week.**
- **Do not grade a failure as EXPIRED.**
- **Do not spend IPADE priority without saying why this is the one.**

---

## 11. Review questions

Answer these weekly, from data, in the brief's retrospective:

- **Points left on the bench**, per league, and which slots.
- Of those, **how many came from a brief's recommendation** versus a slot no
  brief addressed.
- Which theses closed, and were any held past their falsification?
- Did any slot change hands twice in a week?
- Is the waiver analysis saying "nothing" most weeks? If it is producing
  adds every run, it is optimising noise.
- **How many bench points came from our own overrides** of the projection?
  The target is zero.
- Did every contingency resolve the way its trigger said?
- Did research reach every player, at 30–45 searches a run?

### Measured, not yet used

**ESPN's expert weekly rank** — `rank`, with `rankHigh` and `rankLow` — is
frozen at kickoff each week from week 3. Around week 8, with five or six weeks
collected, it gets the backtest's pairwise test: projection alone, expert rank
alone, both. It earns a role only if it beats the projection. Until then it is
shown, never used.
