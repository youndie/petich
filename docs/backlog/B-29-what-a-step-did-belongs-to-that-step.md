---
id: B-29
title: "What a step did is recorded beside its key, not in the payload everyone shares"
status: done
priority: P1
size: L
stage: stage-9-definition
blocked_by: [B-28]
---

# B-29 — "it did not happen" and "it happened and produced nothing" are the same observation today

`compensate` sees the payload and the saga. Anything the action produced — a reservation id, a charge
id — travels through `enrichedPayload`, a map merged across every step. So a compensation cannot tell
a step that never ran from one that ran and recorded nothing, and has to guess.

**That guess is a live money defect.** shashki's `CaptureStep` records a charge id as
`enriched(CHARGE_ID)` and its compensation falls back to `payload.holdId` when the id is absent —
which on a tip is the fare's already-captured hold (youndie/shashki#13). konekt answers the same
question by asking its own ledger (`recorded(orderId, HOLD)`), which works and is per-application
machinery for a question the engine creates.

- **The decision (research D4):** `ctx.record(value)` and `ctx.recorded<T>()`, persisted beside the
  step's key, readable only by that step's own compensation. `ctx.recorded() ?: return` is then the
  guard, structurally.
- **It is what makes [B-18](B-18-the-failed-step-compensates-nothing.md) safe by construction.** That
  item put "a compensation may be called for a step that did not happen" into the contract; this puts
  the evidence in the engine instead of in each implementation's head.
- **Rejected: `PetichStep<P, R>`.** A type parameter on every step that has no result, and a storage
  shape that becomes a function of the step's type. A record keyed by the step key is one value in
  one place.
- **`enrichedPayload` stays** for what it is good at: data the saga carries forward. The two channels
  differ in lifetime and in who reads them, and conflating them is the defect above.
- **Does not cover:** migrating the consumers onto it, which is
  [B-32](B-32-migrate-the-two-consumers.md) and is where it is proved.

- AC: a compensation can distinguish "no record" from "a record"; a step's record is unreadable from
  another step; the storage change is named in the README's upgrade table and passes
  `tools/schema-notes-audit.py`; the corpus has a rule for the record surviving a round trip.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-postgres/src/main/kotlin/PetichTable.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt`,
  `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/PetichStoreConformance.kt`

## Closed 2026-09-20

`ctx.record` writes beside the member's key; `ctx.recorded<T>()` reads back only that member's own,
as a safe cast so a record written by an earlier version of a step reads as absent rather than
bringing the rollback down. `Petich.stepRecords` carries them, both stores keep them in a
`step_records` column defaulted to `{}`, the README's upgrade table names it and the corpus has a
rule for it.

**The case this was built for is the member that does not return.** A step that takes the effect,
records what it did and then throws is the ambiguous failure B-18 exists for. The record is read in a
`finally` and folded into the saga by the two catch blocks as well as by the ordinary path — without
that, its own compensation sees nothing and concludes the step did not happen about a step that did.

**That defect was found because a mutation did NOT fail.** Taking the records from the re-read row
instead of from the caller's petich changed nothing, which could only mean no case covered a record
that existed only in memory. Two did not exist: a member that records and then suspends, and a member
that records and then throws. Both are cases now, and the same mutation fails both.

**A guard of ours went partially blind and named the wrong subject.**
`tools/schema-notes-audit.py` reported `step_records` as missing from `PetichTable`, which declares
it. Its column pattern was `Column<[^>]+>`, and the first column whose type has a generic of its own
— `Column<Map<String, PetichStepRecord>>` — is invisible to it. **The obvious repair would have been
to edit the document it accused.** A guard that fails is fine; one that goes half-blind points at the
innocent file, and the fix is to widen the pattern rather than the documentation.

**A test premise of mine was wrong, and the engine was right.** "A member cannot read another's
record" was first written with the second member calling `ctx.fail`, and its compensation never ran —
correctly: `fail` is a *reported* outcome, so the member reporting it is not undone (B-18's
carve-out). Only a throw reaches that member's own compensation, which is where the scoping can be
shown at all.

**Where it ran:** `:petich-core:allTests`, `:petich-postgres:test` and
`:petich-sqlx4k-postgres:allTests` with `--rerun-tasks` on the Linux box — forced, because a
`clean build` came back in seven seconds with 124 tasks served from the build cache, and a tally read
off restored XMLs is not a run. 357 tests, 0 failures, the corpus against both stores on `jvm` and
`linuxX64`.

**Not done here:** migrating either consumer onto the channel, which is
[B-32](B-32-migrate-the-two-consumers.md) and is where the two defects it was built for are closed.

