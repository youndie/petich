---
id: B-20
title: "Reject after a step has touched the outside world keeps what that step did"
status: done
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

## Closed 2026-09-19

**`Reject` now compensates what ran and still ends `REJECTED`.** One parameter on
`triggerCompensation` for the terminal status, and the refusal goes through the same rollback every
other failure does.

**This is not the rename the item proposed, and the difference is worth stating.** The plan was to
make the compensating refusal the plain name and give the non-compensating one a long explicit name.
That is one name too many: nothing in this repository or in either consumer wants "refuse and keep
what happened", and an API variant that exists for nobody is a trap with documentation attached. The
item's own argument was that the safe thing should be the default — the safe thing is now the only
thing, and if someone turns up needing the other, it arrives then, with a name that says what it
does.

**The alternative the item rejected was rejected for the wrong half of its reason.** "Promote
`Reject` to `Compensate` automatically" was refused because the engine knows how many `Proceed`s
committed rather than whether any had an effect, so an enrichment-only prefix would turn a business
refusal into `FAILED` — and `REJECTED` and `FAILED` are told apart on a replay under the same id.
The first half of that is still true and is why the refusing step is not itself undone. The second
half was avoidable all along: undoing the work and naming the outcome are separate questions, and
only the first one was ever missing. Compensating an enrichment-only prefix costs a few no-op calls,
which is the contract [B-18](B-18-the-failed-step-compensates-nothing.md) already put in writing.

**`Reject` in `POST_PROCESSING` was not a designed case.** Nothing in the documents or the tests
claimed that a refusal after the work is done should keep it; every `Reject` in the suites sat before
`EXECUTION`, which is why the suite was green and silent. It is now defined like any other: the work
comes back.

**The consumers do not change**, which is also the evidence that the defect was latent rather than
active. Every `Reject` in konekt (`PurchaseInterceptors.kt:54`, `:83`, `:120`,
`TopUpInterceptors.kt:38`, `:41`, `:46`, `TariffInterceptors.kt:38`, `:41`) and in shashki
(`OrderSteps.kt:141`, `:142`, `:160`, `SettlementSteps.kt:146`–`:148`) sits before any step with an
effect, so the change adds no-op compensations and nothing else. konekt's
`HoldFundsInterceptor` is the closest call: it records a decline and then refuses, but that is the
refusing step's own write and the refusing step is not compensated.

**Where it ran:** `./gradlew build` on the Linux box, 293 tests, 0 failures, the three new cases on
`jvm` and `linuxX64`. Two mutations after the change was committed: ending the rollback in `FAILED`
fails all three cases, and restoring the old refuse-and-keep branch fails exactly the one about the
effect — which is the shape of the defect the item was opened for.

**Not done here:** an Arrow adapter. `Raise<Rejection>` → `InterceptorResult` is now a mapping with
one sensible target instead of two that look alike, which is what made a twenty-line bridge dangerous
before. Worth writing when somebody asks for it, not before.

