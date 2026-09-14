# Strategy

Standing policy for both leagues. Read this every run, before writing any
recommendation. It outranks the per-run optimisation: if a move the numbers
like conflicts with something here, the numbers lose, or the card explains
why this is the exception.

Lines marked **[DEFAULT]** are starting positions to be overwritten by the
owner. Everything else is settled.

Last reviewed: 2026-09-13

---

## 1. What I am playing for

Both leagues: **win the championship**, not the regular season. Seeding is
worth something but points-for is the honest measure of a roster, and a
league can produce the second-highest total points in the league and still
miss the playoffs on record. So:

- A 1-2 start with top-three points-for is **not** a reason to restructure.
  Record is noisy over a small sample; points-for is not.
- A 3-0 start on low points-for is a warning, not a reason to coast.
- Every decision from week 10 onward is measured against weeks 15–17, not
  against this week.

**[DEFAULT]** Neither league is a rebuild. There is no scenario this season
where selling for next year is correct — these are redraft leagues.

---

## 2. Both leagues are 10-team full PPR. That changes what is valuable.

Two structural facts drive most of what follows.

**Shallow means stars matter and depth does not.** In a 10-team league every
roster looks solid, so the edge comes from difference-makers, not from being
well-rounded. Flex-level players are close to worthless as assets because the
wire reliably replaces mid-level production. The corollary is that a
two-for-one trade almost always favours the side receiving the single best
player — and in this format that side should usually be me.

**Full PPR pays for volume, not explosiveness.** A reception is a point. A
back who catches five passes gets five points before he gains a yard. This
means:

- A committee back who loses the goal line *and* third downs is uniquely bad
  here. He collects the least valuable touches available.
- A high-target slot receiver with no touchdowns is still startable.
- Target share and route participation are the floor. Touchdown rate is the
  noise.
- When two players project within a point of each other, take the one whose
  points come from volume rather than scoring.

---

## 3. The churn rule

**This exists because of a real failure.** Across consecutive runs the
recommendation went Allgeier → Likely → Worthy: each step individually
defensible, the sequence value-destroying, and the last step traded a 9.5 TE
for an 8.0 WR. Dropping long-term bench assets for short-term fixes is a
known way to lose value. Every add has an opportunity cost.

### Thresholds

| Situation | Required gain |
|---|---|
| Add a free agent over a bench player | **[DEFAULT]** +1.5 projected points |
| Drop a player acquired in the last 7 days | **[DEFAULT]** +3.0, **and** a stated reason his thesis broke |
| Drop a player acquired in the last 3 days | Do not. Unless he is ruled out for the season |
| Spend IPADE waiver priority | See §6 |

A move that does not clear the bar is not a Do-first card. It can be a
Candidate with a trigger.

### The question to ask

Not *"is this player the best use of the slot?"* — that re-litigates the
whole roster every run and flips on noise. Ask:

> **Has the reason I added him changed?**

If the thesis is intact, he stays, even if someone now projects 0.4 higher.
If the thesis broke — the starter came back, the role never materialised, the
snap share went the wrong way — he goes, and the card says which.

### Never churn the same slot twice in a week

If a roster spot changed hands in the last 7 days, it is closed unless a
player in it is ruled out. Rotating one bench seat through three players is
the signature of optimising noise.

---

## 4. What a good add looks like

Not a projection. **A role change with a name attached.**

In order of what actually predicts production:

1. **Opportunity.** Snap share, target share, expected fantasy points share.
   Volume is the single best predictor of fantasy points over any real
   sample, and extreme efficiency regresses.
2. **A vacated role.** Someone got hurt, traded, benched, or suspended, and
   this player inherits the work. Best case: he already plays the snaps and
   simply absorbs the targets.
3. **Ownership anomaly.** Widely rostered elsewhere and free here is the
   strongest single wire signal in a shallow league — it usually means a
   manager blundered, not that the player is bad. This is the `why=OWNED`
   band.
4. **Ownership velocity.** Rising fast from a low base means the wider market
   has seen something. Useful, but it is a lagging confirmation of 1 and 2.

**Not a good add:** a hot week on low volume. That is a sell signal, not a
buy signal.

Roughly half the players on a championship roster went undrafted, so the wire
does win seasons — but through a small number of high-conviction adds, not
through turnover. Both things are true.

---

## 5. Lineup policy

**Start the studs.** A matchup grade explains a projection; it never benches
a top-12 player at his position. If the grade and the tier disagree, the tier
wins.

**The grade decides close calls only.** Two players within ~1.5 points is
where matchup, game script and target share break the tie.

**Never bench a player because he is "due."** And never start one for the
same reason.

**Game script matters at RB, not WR.** A heavy favourite's back gets fourth
quarter carries. A heavy underdog's receiver gets garbage-time targets. These
pull in opposite directions and both are real.

**[DEFAULT]** Kicker and DST are streamed on matchup, every week, without
sentiment. In a 10-team league the wire always has a usable one.

---

## 6. Waiver priority

The two leagues are opposite and this is the most expensive thing to get
wrong.

**Chem — priority resets weekly.** It is not a resource. Claim anything worth
claiming. A speculative handcuff or a one-week streamer costs nothing
lasting. Be aggressive.

**IPADE — priority never resets.** One claim drops me to last for the rest of
the season. So:

- **Free agents are still free.** Only waiver claims consume priority. Always
  check `status` before treating an add as expensive.
- **[DEFAULT]** Spend priority only on a player who would start immediately,
  or who is a genuine league-winner — the kind of add that shows up on a
  championship roster. Never on a bye-week patch, never on a handcuff,
  never on a streamer.
- Once priority is spent, it is gone. Treat it like a single trade chip.

---

## 7. Trades

**Only propose what both sides gain.** A proposal that helps only me is a
wish. Compute both teams' optimal lineups before and after; if their total
does not rise, it does not get offered. State their gain in the headline —
hiding it is what makes an offer read as lopsided and get declined unread.

**Consolidate.** In a shallow league, two starters for one better starter is
the right direction, because the roster spot I open is refillable from the
wire and the difference-maker is not. Target two-for-one where I receive the
best player in the deal.

**Trade into the other side's urgency.** A team with two elite quarterbacks
in a one-QB league is paying for nothing every week. A team stacked at one
position and thin at another will move value to fix the hole. Structural
surplus is the thing to look for, not name value.

**Buy low on usage, sell high on efficiency.** Buy the player with strong
underlying volume and bad results. Sell the player with a hot streak built on
touchdowns he will not repeat.

**[DEFAULT] Untouchable regardless of the math:** any player who is top-5 at
his position. The optimiser will occasionally propose trading an elite
quarterback for a flex receiver because a backup appears to backfill the
slot. It is always wrong.

**Deadline is 12/2 in both leagues.** Do not wait for it. Markets are active
and honest in weeks 9–11; by the deadline everyone is either panicked or
checked out.

---

## 8. The calendar

| When | What changes |
|---|---|
| Weeks 1–3 | Sample is noise. Do not trade on three games. React to **role** news only, never to box scores. |
| Weeks 4–6 | Roles have settled. First real window to buy low on a slow starter with good usage. |
| **Week 7** | **Jacksonville problem in IPADE.** Tuten, the Jaguars D/ST and Cam Little are all out together. Chem is also exposed: Allen, McLaurin and JAX pieces. Solve this by **week 5**, by trade or by roster shape — not with a week-6 waiver scramble, and in IPADE never by spending priority. |
| Weeks 9–11 | Prime trade window. Contenders and sellers are both identifiable and neither is desperate yet. |
| Week 12 | Start evaluating every asset against the weeks 15–17 schedule, not this week's. |
| 12/2 | Trade deadline, both leagues. Last chance to change the roster other than the wire. |
| Weeks 15–17 | Playoffs. Start studs regardless of matchup. The only question left is which marginal starter has the softer draw. |

---

## 9. Known roster shape

Facts that should inform every run. Update when they change.

**Chem** — full PPR, flex RB/WR only, priority resets weekly.
**IPADE** — full PPR, flex RB/WR **and TE**, priority never resets.

The IPADE flex taking a tight end means a second startable TE has real value
there and none in Chem. Do not carry one in Chem.

**[DEFAULT]** No position is being punted in either league.

---

## 10. What I do not want

- **Do not manufacture cards.** An empty Do-first section is a legitimate and
  common answer, especially mid-slate. Four cards a day is churn wearing a
  suit.
- **Do not re-derive strategy every run.** That is what this document and the
  decision log are for. Start from what is already decided and ask what
  changed.
- **Do not chase last week's points.** Chase roles.
- **Do not propose a move worth under a point.** The transaction cost of
  being wrong exceeds the gain.
- **Do not bench a stud on a matchup grade.**
- **Do not spend IPADE priority without saying, in the card, why this is the
  one.**

---

## 11. Review questions

Answer these when this document is next reviewed:

- Which recommendations from the log proved right? Which theses broke, and
  did I drop them fast enough or defend them too long?
- Is the +1.5 threshold too low? Too high?
- Did the week 7 plan work, and what did it cost?
- Is there a position where I am repeatedly making moves? That is either a
  real hole or a tell that I am optimising noise.
