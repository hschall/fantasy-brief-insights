# Strategy

Standing policy for both leagues. Read this every run, before writing any
recommendation. It outranks the per-run optimisation: if a move the numbers
like conflicts with something here, the numbers lose, or the card explains
why this is the exception.

Last reviewed: 2026-09-24, after an 0-4 start. Section 0 is new and records
what that start taught. Sections 1-3 are rewritten from it.

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

---

## 1. Lineup policy — first, because it is where games are lost

### The three cases

Every lineup decision falls into exactly one of these, and the case decides
the method:

**Projection and volume agree.** Start the one they both prefer. No thesis,
no decision-log entry. This is most decisions and it should take no effort.

**A clear volume gap.** One player's opportunity — targets and carries — is
materially larger and not shrinking. Follow volume, even against a small
projection edge. *Coker 9 opportunities against McLaurin 4 was this case;
volume was right and the brief was wrong.*

**Close on both.** Similar projection, similar volume. This is a coin flip
and no method calls it reliably — Warren and Tuten had identical week-1
volume, 16 and 16. **Default to projection and do not write a thesis.**
Spending analysis on a coin flip produces confident reasoning about noise.

### What never justifies overriding projection

**Last week's points.** This is the rule that cost the most and it is not
negotiable. A player who scored well is not thereby better; a player who
scored badly is not thereby worse. If the override rests on a box score
rather than a role, it does not happen.

The only legitimate overrides are **role facts with a name attached**: an
injury, a quarterback change, a depth-chart move, a confirmed snap-share
shift. And the card states which one.

### Start the studs

A top-12 player at his position starts through a bad week. The grade
explains the projection; it never benches him.

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
- When two players project within a point, take the one whose points come
  from volume rather than scoring.

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
player — never a stud — on:

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

---

## 10. What I do not want

- **Do not chase last week's points.** It is the most expensive mistake this
  system has made.
- **Do not manufacture cards.** An empty section is a legitimate answer.
- **Do not write a thesis on a coin flip.**
- **Do not bench a stud on one bad week.**
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
