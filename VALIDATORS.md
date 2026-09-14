# Pre-publish validators

Every rule here exists because a card shipped wrong. Nothing is published
unless `validate.py` exits zero — chain it with `&&`, never run it alongside
and eyeball the output.

    python3 validate.py daily-<leagueId>.json league-<leagueId>.json && <publish>

`validate.py` in this repo is the **only** copy. An inline duplicate in
RUNBOOK.md drifted from it and carried three rules the file lacked; they are
merged in and the runbook now points here instead.

## Current rules

| Rule | Symptom that caused it | Date |
|---|---|---|
| **ALREADY DONE** | Recommended swapping Warren in for Tuten when the owner had already made the swap. The slot column was in the dump the card was built from. | 2026-09-13 |
| **CANNOT DROP** | Named Stafford as the drop after his game had kicked off. ESPN locks a player once his game starts. | 2026-09-13 |
| **… except a future CLAIM** | The rule above then blocked a legitimate Sunday-evening waiver claim. A drop executes when its action executes: for a claim that is `clearsAt`, by which time the week has rolled. Without the carve-out, no claim made after the early games can ever pass. | 2026-09-14 |
| **ALREADY YOURS** | Guard against recommending an add, or listing a candidate, already on the roster. | 2026-09-13 |
| **UNKNOWN ID** | A wrong playerId publishes successfully and attaches to nobody. The app counts orphans. | prior |
| **NO DEADLINE** | A card with no deadline never expires, so its button never stops firing. | 2026-09-13 |
| **ONE-SIDED TRADE** | A proposal where the other side does not gain is a wish. | 2026-09-13 |
| **CARD CAP** | More than four Do-first cards means the section is being padded. | 2026-09-13 |

## Checks that need judgement, not a rule

These were real mistakes that no script catches.

- **Wrong league's projection.** Quoted Mark Andrews at 10.1 (IPADE, full PPR)
  in a Chem recommendation while Chem was half PPR. Every number in a card
  must come from the same league file in the same script run.
- **Stale snapshot.** Quoted Andrews at 8.4 from a six-hour-old file against
  Pitts at 9.8 from the current one. One snapshot per brief.
- **Unread settings change.** Chem switched 0.5 → 1.0 PPR mid-evening and the
  analysis carried on in half PPR. `settings.scoring` was present and correct
  in all three files opened that night. Diff settings before any player.
- **Projection-only screening.** "Nothing on the wire beats Tuten" was a sort,
  not research. The value was Tre Tucker at a lower projection with a role the
  number had not caught. Later, the same error in reverse: recommended
  dropping Monangai, who then scored 20.4.
- **Valuing the slot rather than the player.** Recommended dropping Isaiah
  Likely because a second TE cannot enter a RB/WR flex. Structurally true, and
  still wrong — Likely scored 16.9 and the rostered TE scored 0.0.
- **Blind overwrite.** The runbook says publish both files. It does not say to
  read what is already there. A prior brief held a live trade proposal and a
  warning worth keeping; overwriting without merging would have destroyed
  both. Read the existing file before replacing it.

## The shape of most of these

Live data was pulled, the contradicting field was printed, and the write-up
came from memory anyway. The rules above turn the mechanical ones into a gate.
The rest belong in the runbook as an ordered procedure, not as a reminder to
be careful.
