---
id: B-29
title: "What a step did is recorded beside its key, not in the payload everyone shares"
status: open
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
