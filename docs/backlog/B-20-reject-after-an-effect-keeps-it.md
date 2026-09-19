---
id: B-20
title: "Reject after a step has touched the outside world keeps what that step did"
status: wip
priority: P1
size: S/M
stage: stage-6-recovery
---

# B-20 — two ways to refuse a saga, and the cheap-looking one skips the rollback

`Reject` writes `REJECTED` and returns, calling no `compensate()` at all (`Petich.kt:774`);
`Compensate` runs the rollback. The difference is not in the documentation, not in the KDoc, and not
in any test name — every `Reject` in the suites happens to sit before `EXECUTION`, so the suite is
green and says nothing. An interceptor author choosing the one whose name matches "the business
refused" gets the one that keeps the reservations.

Choosing correctly requires knowing whether any earlier step has already touched the outside world.
That is knowledge about other people's steps, which an interceptor does not have by construction —
`supports()` and a phase is all it declares, and the chain it lands in is assembled elsewhere
(see `B-21`). The engine does know the position.

- **The decision is to make the safe result the default, not to guard the unsafe one.** Renaming so
  that the compensating refusal is the plain one and the non-compensating one carries a long
  explicit name (the `EXECUTION`-and-later case being the dangerous one) removes the trap instead of
  reporting it. A guard leaves the sharp edge and adds a rule about it.
- **Rejected: promoting `Reject` to `Compensate` automatically** once anything has run. The engine
  knows how many `Proceed`s were committed, not whether any of them had an effect, and an
  `ENRICHMENT` step returns `Proceed` like everything else. The promotion would fire on an
  enrichment-only prefix and turn a business refusal into `FAILED` — and those two are told apart on
  replay of a terminal saga, so a client repeating a request under one idempotency key would get a
  different answer than the first time. That exact defect has been fixed in this engine once
  already; re-introducing it to close this one is a bad trade.
- **A phase-scoped refusal is the fallback** if the rename is judged too expensive for a published
  API: `Reject` returned at or after the first committed `EXECUTION` step fails loudly. It is
  computable from state the engine already has.
- **`Reject` in `POST_PROCESSING` is not a designed case.** Nothing in the documents or the tests
  claims that a refusal after the work is done should keep it. If it turns out to be wanted, it has
  to be named — an undefined case is what this item is about.
- **Does not cover:** an Arrow adapter. A twenty-line `Raise<Rejection>` → `InterceptorResult`
  bridge is exactly what this trap makes dangerous, because the mapping looks total and silently
  picks the non-compensating branch. Once the naming is fixed the adapter becomes a reasonable
  thing to want, and not before.

- AC: the two refusals are distinguishable by name without reading the engine; a refusal returned
  after a step has committed in `EXECUTION` or later either compensates or fails loudly, proved by a
  test that leaves an effect behind and asserts it was undone; the README table saying which does
  what stays true.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonTest/kotlin/io/github/youndie/petich/StockMovePetichEngineTest.kt`
