# Backtest — 2025 season

Run 2026-09-26, before building anything in the player-analysis spec. Every
idea below sounded right. Most did not survive measurement. This file exists
so a future session does not re-propose them without new evidence.

## The question

Can anything beat ESPN's weekly projection at the decision that matters —
picking the better of two players at the same position in the same week?

## The data

- ESPN 2025 regular season, **weeks 1–17**. Week 18 excluded: contenders
  rested starters.
- Every player's weekly **projection** and **actual**, with targets, carries
  and pass attempts. One request to
  `/seasons/2025/players?view=kona_player_info` returns the whole pool.
- **Scored by us, full PPR.** `appliedTotal` needs a league's scoring settings
  and the 2025 route has none — it came back on 0 of 20,661 rows. The scoring
  table reproduces Chem's own week-2 numbers exactly (Allen 40.82, Jefferson
  8.50) and is asserted on every run.
- **The projections are pre-game.** A hindsight projection would not miss by
  5.88 points on average or get close calls only 56% right.
- 2025 calibrates the **method**, never a player: whether usage predicts
  points in general does not depend on who played where.

Script: `backtest_2025.py`. Results: `results-2025.json`.

## Results

### A. Does trailing volume improve on ESPN's projection?

2,005 startable player-weeks, weeks 5–17; 43,632 same-position pairs.

| Method | Start/sit | Close calls (≤3 pts) |
|---|---|---|
| **ESPN projection** | **65.2%** | **56.2%** |
| 90% ESPN + 10% volume | 65.0% | 55.8% |
| 50% ESPN + 50% volume | 63.3% | 52.5% |
| Volume, last 4 weeks | 61.7% | 52.1% |
| Volume, last week | 58.7% | 50.3% |

Fitted on weeks 5–11: *actual = 0.23 + 1.00 × ESPN − 0.06 × volume*. Tested on
12–17: a tie with ESPN alone.

**Volume adds nothing.** ESPN's projection already contains it; adding it
again re-adds what is priced in, plus noise. The week-2 check of 2026 said the
same on 233 players.

### B. Do reliability components persist from one half-season to the next?

163 players with 5+ games in each half. A component that does not carry over
cannot predict anything.

| Component | All | QB | RB | WR | TE |
|---|---|---|---|---|---|
| Floor rate (hit 80% of projection) | 0.02 | −0.14 | −0.08 | 0.11 | −0.10 |
| Usage stability | 0.37 | 0.03 | **0.43** | 0.18 | 0.05 |
| Bias (actual ÷ projected) | 0.03 | −0.24 | −0.04 | 0.14 | −0.19 |
| Volatility | 0.20 | −0.17 | 0.30 | 0.27 | −0.22 |

Usage stability → second-half floor: **0.01**.

The proposed score (0.6 usage + 0.4 floor) on weeks 1–8 against floor rate on
9–17: top third **52.6%**, middle 53.3%, bottom third **56.0%** — slightly
backwards.

**Beating or missing the projection is not a trait.** Once role, volume and
matchup are priced in, what is left over is luck. Usage stability is real for
running backs, and does not predict whether they hit their projection.

### C. Do volatile players boom and bust more?

Pairs projected within 2 points, split by first-half volatility:

| | Boom (≥1.5× proj) | Bust (<0.5× proj) |
|---|---|---|
| More volatile | 17.1% | 26.5% |
| Steadier | 16.2% | 28.2% |

**No.** The favoured/underdog rule would have been choosing on noise.

### D. Shrinkage, and correcting ESPN by a player's bias

Estimating rest-of-season floor rate from a player's first games, the best
shrinkage toward the position average was **K = 35–60** — his own record
barely beats the norm. Bias correction lowered start/sit accuracy at every K
tested (ESPN 64.8%; corrected 63.5–64.5%).

### E. Does ESPN under-project its best players?

| Weekly projection rank | Actual ÷ projected | Hit 80% of projection |
|---|---|---|
| Top 12 at position | 0.95 | 56.7% |
| 13–24 | 0.97 | 55.6% |
| 25+ | 0.92 | 46.8% |

**No.** Top players deliver like everyone else. (All tiers sit under 1.00
because ESPN projects everyone as if they finish the game healthy; it cancels
out in any comparison.)

## Ruled out

| Idea | Why |
|---|---|
| Volume overrides a projection | A, and 2026 week 2 |
| Reliability score | B |
| Correcting projections by player bias | B, D |
| Favoured → steady, underdog → volatile | C |
| "Always start studs" | E — ESPN rates them fairly already |

## What survived

- **ESPN's projection is the lineup baseline.** 65% on start/sit, and no
  statistical adjustment improved it.
- **Close calls are coin flips.** Even ESPN gets 56%. Take the projection and
  spend no analysis there.
- **The edge is information ESPN has not priced yet** — injuries,
  inactives, quarterback and role changes that break after the projection is
  set. No backtest can measure that; it is where research earns its keep.
- **Running backs' usage share is a stable, descriptive fact.** Show it; do
  not score it.

## Not yet tested

**ESPN's expert weekly rank** (`rank`, `rankHigh`, `rankLow` in the league
file) tracks the projection rank within about two places and is a separate
signal. 2025 holds no rankings, so it cannot be backtested. It is frozen at
kickoff each week from 2026 week 3, and gets the same pairwise test once
five or six weeks exist — along with whether a wide expert range predicts a
boom-or-bust week.
