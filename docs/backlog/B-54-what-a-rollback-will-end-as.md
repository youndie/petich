---
id: B-54
title: "A resumed rollback forgets it was a refusal, and can drag a terminal saga back"
status: done
priority: P1
size: S
stage: stage-11-review
blocked_by: []
---

# B-54 — two defects, one missing fact: what this rollback is going to end as

**A resumed rollback ends `FAILED` even when it was a refusal.** `doProcess` resumes an interrupted
rollback with `triggerCompensation(currentPetich, "Resuming compensation")` — the default
`terminalStatus` is `FAILED`. The target status is written nowhere, so a saga refused on business
grounds whose process died mid-rollback is finished by the sweeper as `FAILED`. The client asking
again is told the server broke rather than that it was refused. **B-20 exists to keep those two
apart**, and this is the one path that loses the distinction.

**And a terminal saga can be dragged back into `COMPENSATING`.** `forceUpdateStateWithRetry` re-reads
`latest` on a conflict and writes its own status over it without looking at `latest.status`. A replica
paused longer than `stuckAfter` — a GC pause, a frozen VM — wakes after another replica's sweeper has
finished the rollback as `FAILED`, overwrites it, and compensates a second time. The `stuckAfter`
formula makes this rare; it does not make it impossible.

Both are the same missing fact written down in two places.

## Acceptance

- The status a rollback will end under is persisted with the mark that starts it, and the resume path
  reads it instead of defaulting.
- A saga refused with `ctx.reject`, its process killed mid-rollback, finished by a second pass, ends
  `REJECTED`.
- `forceUpdateStateWithRetry` does not overwrite a row that is already terminal; it stops. The cheap
  invariant is enough and the expensive one is not needed.
- A test for the second: a terminal row, a stale writer, and the row still terminal afterwards.

## Findings

**The item was two defects; the work was three, and the third is why the first two are real.**
`compensatingFromIndex` — B-53's field, merged the day before — was **persisted by neither store**.
The model carried it, the engine wrote it and read it, `PetichTable`, `ExposedPetichRepository`,
`Schema.kt` and the native store had never heard of it, and the comment in `triggerCompensation`
said its value "was persisted by the loop further down". Writing `compensatingTowards` beside it is
one edit to the same five places, so both were folded in here rather than opened as a third item.
The work grew past its `S`; the direction did not change.

**The tests that said B-53 worked could not have seen this.** They use a repository that keeps the
`Petich` object whole, which answers every question about persistence with a yes. That is the same
failure shashki's B-92 found in a mock that was kinder than production, and the general form is
recorded against D4 in the research document: a field the model carries is not a field the database
keeps, and the only check that can tell is one that runs against the real thing.

**So the claim about storage is made where it can be made.** `PetichStoreConformance` carries both
fields at **non-default** values — in the "every field lands" case for the update path and in "a
rollback that gave up" for the insert path — because a field left at its default cannot tell a store
that writes the column from one that has never heard of it. Verified by mutation: dropping
`compensatingTowards` from the Exposed writes fails both cases by name, on both paths.

**The terminal-row invariant sits in `forceUpdateStateWithRetry` because every status the engine
writes goes through it.** It returns the stored row rather than throwing — the row is the answer, and
a caller that wanted to write a status can see for itself that the saga is finished. One caller needs
more than "not written": `triggerCompensation` would otherwise walk the members and undo everything a
second time, so it checks the row it got back and stops. `doProcess` already refused a terminal saga
at the door; this is the same rule for a pass that is already inside.

**And the refusal is counted.** `PetichEngineMetrics.onTerminalWriteRefused(type, attempted)` is the
only outward sign that two passes are working the same saga: the row is correct, nothing throws, the
client is answered. A steady line there is a `stuckAfter` too short, which is a thing an operator can
act on and would otherwise never learn.

**Checked by two mutations, one per half.** Defaulting the resume to `FAILED` again fails the refusal
test with `expected: <REJECTED> but was: <FAILED>`; removing the terminal guard fails the second with
`expected: <[hold]> but was: <[hold, refund, release]>` — the whole saga undone twice, which is the
defect in one line.

**Verification.** `./gradlew build` on the Linux box, exit code read rather than piped: green,
including `petich-core:linuxX64Test` (178 tests) and the conformance corpus against a real Postgres
through both stores on JVM and on linuxX64.

**Left out deliberately.** The expensive invariant the item mentions — a store-level constraint
forbidding a transition out of a terminal status — is not here. The cheap one covers every write the
engine makes, and the expensive one would be a migration in every consumer to catch a writer that is
not the engine.
