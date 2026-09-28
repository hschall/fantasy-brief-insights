# Edition payload — `edition-<leagueId>.json`

The Insights tab renders entirely from this file. **The app computes nothing
on this screen, and nothing in it leaves the app — the payload carries no URLs**: every number is computed by `edition.py` from ESPN's data,
every sentence comes from the brief run's research. Layout and interaction are
specified in *AVIATO Insights — the paper* (reference screen 26a); logic in
`AVIATO-designer-handoff.docx` §11–13.

Schema version: **1**. A client that sees a higher `schema` shows the last edition
it understood rather than guessing.

## Top level

| Key | Content |
|---|---|
| `schema` | 1 |
| `edition` | `id` (per publish; keys Do-first Done state), `leagueId`, `leagueName`, `label` ("CHEM EDITION"), `week`, `record` ("0–2"), `dateline`, `time` (Mexico City), `state` ("BEFORE INACTIVES" / "GAMES UNDER WAY"), `generatedAt`, `dryRun` |
| `chart` | `slots` (week slots in every chart strip), `slotsSource` ("espn" or "default"), `currentWeek` |
| `front` | lead story and matchup (below) |
| `doFirst` | cards, soonest first (below) |
| `roster` | `deck`, `asSet`, `best`, `lines[]` — starters in lineup order, then bench by projection |
| `wire` | `headline`, `deck`, `candidates[]` (same line shape, plus `fa`), `market`, `byes` |
| `notes` | `cannotSee[] {label, body}`, `lastWeek` (string or null), `sources` |

## `front`

- `lead`: `kicker`, `headline`, `deck`, `byline`, `dateline` (bold lead-in), `body`, `players[] {playerId, name, caption, logo}` — one mugshot per player named in the headline.
- `matchup`: `me` / `them` `{name, live, proj}`, `winProb` (0–1, ESPN's), `slots[] {slot, mine{name,pos,proj}, theirs{name,value,final}}` for the butterfly chart, `listings[] {time, starters[], bench[]}`.

## `doFirst`

`headline`, `deck`, `note` (always shown), `empty` (one line, or null), and `cards[]`:

| Key | Content |
|---|---|
| `id` | stable per card per edition — Done state is stored against it |
| `urgency` | `today` (red) · `week` (blue) · `ahead` (ochre) |
| `due` | ISO date/time; cards are already sorted by it |
| `tag`, `short` | full deadline tag for the card; short form for the front-page teaser |
| `headline`, `reason` | text |
| `rows[] {label, value}` | FALLBACK, IF ACTIVE, THE DROP, COVER … |
| `playerId` | mugshot, or null (bye card) |
| `primary {label, action}` | the move, in-app: `swap {start, bench}` opens the app's swap sheet, `add {playerId, drop}` its claim flow, `tab {tab}` opens matchup / roster / wire |
| `secondary {label, action}` | `sheet {playerId}` opens that sheet on the paper; `byes` jumps to Byes ahead |

## Line — roster and wire

| Key | Content |
|---|---|
| `playerId`, `name`, `pos`, `team`, `teamName`, `logo` | identity; `logo` is the ESPN logo slug. Headshot: `a.espncdn.com/i/headshots/nfl/players/full/{playerId}.png`; logo: `a.espncdn.com/i/teamlogos/nfl/500/{logo}.png` |
| `slotLine` | "RB · PIT", "FLEX · WR · CAR", "BE · RB · JAX", "FA · RB · SEA", "D/ST · MIN" |
| `starting`, `questionable` | booleans; `questionable` draws the ochre [Q] |
| `tag` | ELITE · FAVORABLE · NEUTRAL · TOUGH · AVOID |
| `pct`, `proj`, `normal`, `normalEstimated` | meter and fine print: "+25% · 15.0 vs 12.0", asterisk when estimated |
| `reason` | italic line under the name (on wire lines: the ADD IF condition) |
| `fa` | wire only: `owned`, `chg`, `chip` ("WATCH" / "ADD"). No meter on wire lines |
| `sheet` | the open sheet (below) |

## `sheet`

| Key | Content |
|---|---|
| `slotLine`, `opponent`, `kickoff` | "STARTING · RB", "PIT vs CIN", "Sun 11:00" |
| `deck` | italic read |
| `verdict {kind, text}` | kind: START · IF_ACTIVE · BENCH · WATCH · ADD; text is ready to print |
| `flags[]` | raw flag ids (QUESTIONABLE words mapped per docx §11.9) |
| `figures {experts, projMove, matchup}` | null → render "—" / "not researched" literally |
| `range {rank, best, worst}` | or null → strip omitted |
| `weeks[]` | one entry per finished week: `{week, pts, proj, touches, eff, share, posAvgPts, posAvgTouches, posAvgEff, posAvgShare}` or **null for an absence**. `posAvg*` = the average **starter** at his position in this league that week. Weeks after the last entry are future: empty slots up to `chart.slots` |
| `current {week, proj}` | this week's projection — the dashed outline |
| `shareKey` | "carries" · "targets" · null (QB, K, D/ST) |
| `series` | per chart row `{last, avg}` — printed in the row header, never recomputed |
| `table[] {label, value, style}` | style: `plain` · `italic` (CHECKED) · `priced` (bold first sentence) |
| `challengers {title, self, rows[]}` | title "CLOSEST CHALLENGER" / "COULD START OVER"; rows `{where, playerId, name, proj, gap, gapLabel}`; `gapLabel` "coin flip" / "on the line" / null |
| `logic` | the LOGIC paragraph |
| `next {playerId, surname}` | NEXT ↓ target, or null for the last |

K and D/ST: `weeks[]` carry `{week, pts, proj}` only, and the table has availability and checked only.

## `wire.market` and `wire.byes`

- `market.kicker` / `market.defence`: `{action: "HOLD" | "SWAP", rows[] {name, proj, mine}}` — ours first.
- `byes`: `weeks[] {week, starters, bench}` for every remaining week, `worstWeek` (≥ 3 starters, else null), `caption`.

## Client-side only (never in the payload)

- Do-first Done state: set of card `id`s, stored per `edition.id`.
- Watchlist: shared with the Wire tab, per league.
- Night edition: token swap.

## Pending `publish` additions

- `settings.regularSeasonWeeks` from ESPN `scheduleSettings.matchupPeriodCount` — until then `chart.slotsSource` is "default".
- `proTeams[].location` (city) — until then captions read "RB · PIT" instead of "RB · Pittsburgh".

## The text layer — `text-<leagueId>.json`

Written by the brief run; everything research produces, nothing a number can
say. `edition.py` merges it with ESPN's data. Every name the builder resolves
(fallbacks, drops) must be the player's exact ESPN name.

| Key | Content |
|---|---|
| `lead` | `kicker`, `headline`, `deck`, `byline`, `dateline`, `body`, `players[] {playerId, caption}` — leads with the bad news about his own players |
| `doFirst` | `headline`, `deck`, `note`, `empty` (one true sentence for this moment when there are no cards), `cards[]` — each `{id, kind, playerId?, headline, reason, rows[[label, value]]}`; `kind` = `contingency` (one per START IF ACTIVE starter), `conditionalClaim` / `claim` (+ `deadline` YYYY-MM-DD), `byePlan` (+ `week`). Ids are stable within the week: `w3-warren-inactive` |
| `roster` | `deck` — one sentence from the lineup check |
| `wire` | `headline`, `deck`, `candidates[]` — player ids, only the genuinely close |
| `players` | keyed by ESPN id, **every rostered player and every wire candidate**: `deck`, `reason` (one line, ≤ ~90 characters), `flags[]`, `availability`, `role`, `competition`, `passer`, `checked`, `vsProjection {tag, text}` or null, `verdict` (START · BENCH · CONDITIONAL · WATCH · ADD), `trigger`, `fallback` (exact name), `drop` (exact name, wire), `addif` (wire), `logic`, `matchupGrade` or null |
| `unpriced` | `{id: +1 or -1}` — one notch on the matchup tag, only for news the projection hasn't priced |
| `notes` | `cannotSee[] {label, body}`, `sources` |

`edition.py` stops if any player lacks an entry: every player is researched, every run.

## Writing the front page — the week's rhythm

The lead story follows the week. Pick the mode from the run's day and time
(Mexico City) and set the kicker to match:

| When | Kicker | Lead with |
|---|---|---|
| Tuesday, before the waiver run | TUESDAY REVIEW | Last week's result — the score, what won or lost it, points left on the bench (from the archive) — then the new week's outlook |
| Tuesday evening to Wednesday, before claims process | WAIVER WIRE | The news behind the risers, tonight's claim strategy, and who to drop |
| Thursday to Saturday | MIDWEEK | The week's news: injuries, practice reports, depth charts, surprise adds by leaguemates |
| Sunday, before kickoff | BEFORE KICKOFF | Game day: inactives, contingencies, lineup risks |
| Sunday night to Monday | SUNDAY NIGHT | The result so far, the injuries that change next week, what is left to play |

Within every mode, lead with the bad news about his own players.

Rules that hold in every mode:

- **An injury exit is not a performance.** A player who left injured gets a
  LOGIC that says the call can't be graded and names what matters for next
  week — never "the bench won".
- **After games, LOGIC grades the projection's call** against the result once
  his game is final. One week of results never overrides the projection.
- **Game nights may skip the wire.** An empty `candidates` list with a wire
  headline saying it wasn't researched is honest; say when it will be.
- **`doFirst.empty`** is written for the moment ("Nothing left to decide this
  week…"), not a stock line.
