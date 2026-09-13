# Pre-publish validators

Every rule here exists because a card shipped wrong. Nothing is published
unless `validate.py` exits zero — chain it with `&&`, never run it alongside
and eyeball the output.

    python3 validate.py daily-<leagueId>.json league-<leagueId>.json && <publish>

## Current rules

| Rule | Symptom that caused it | Date |
|---|---|---|
| **ALREADY DONE** | Recommended swapping Warren in for Tuten when the owner had already made the swap. The slot column was in the dump I built the card from. | 2026-09-13 |
| **CANNOT DROP** | Named Stafford as the drop after his game had kicked off. ESPN locks a player once his game starts; he is undroppable until the week rolls. | 2026-09-13 |
| **ALREADY YOURS** | Guard against recommending an add or listing a candidate already on the roster. Not yet observed in the wild. | 2026-09-13 |
| **UNKNOWN ID** | A wrong playerId publishes successfully and attaches to nobody. The app counts orphans and says so. | prior |

## Checks that are not automated yet

These were real mistakes but need judgement rather than a rule:

- **Wrong league's projection.** Quoted Mark Andrews at 10.1 (IPADE, full PPR)
  in a Chem recommendation when Chem was half PPR at the time. Fix: every
  number in a card comes from the same league file in the same script run.
- **Stale snapshot.** Quoted Andrews at 8.4 from a six-hour-old file against
  Pitts at 9.8 from the current one. Fix: one snapshot per brief, pulled at
  the start, never mixed with recall.
- **Unread settings change.** Chem switched 0.5 -> 1.0 PPR mid-evening and the
  analysis carried on reasoning in half PPR. `settings.scoring` was present
  and correct in all three files opened that night. Fix: diff settings
  against the previous snapshot before looking at a single player.
- **Projection-only screening.** Answered "nothing on the wire beats Tuten" by
  sorting on projection, which is what the app already does. The value was
  Tre Tucker at a lower projection with a role the number had not caught.

## The shape of every error so far

Live data was pulled, the contradicting field was printed, and the write-up
came from memory anyway. The rules above turn the ones that can be mechanical
into a gate; the rest belong in the step-by-step instructions as an ordered
procedure, not as a reminder to be careful.
