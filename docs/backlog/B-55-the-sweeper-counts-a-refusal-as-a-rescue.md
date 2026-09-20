---
id: B-55
title: "The sweeper counts a refusal as a rescue, and one failure blocks both queues"
status: done
priority: P2
size: S
stage: stage-11-review
blocked_by: []
---

# B-55 — two things the sweeper reports that are not what happened

**A refusal is counted as a revival.** `sweepStuck` ignores what `engine.process` returns, so a saga
refused for a changed chain — `PetichResult.SystemFailure` — increments `revived` and calls
`onRevived`. The number an operator watches to see recovery working counts sagas that were not
recovered, and the counter B-44 added for exactly that case fires beside it saying the opposite.

**One failure blocks both queues.** `sweep()` and `sweepStuck()` sit in one `try`. While `findExpired`
is failing — a database that cannot serve that query, an index being rebuilt — the stranded queue is
not processed at all, and the two have nothing to do with each other.

**And a throw from `onWorkerFailure` ends the worker.** It is called from the `catch`, so an
application whose reporting throws stops the sweeper for the life of the process, silently.

## Acceptance

- `revived` counts sagas the engine actually moved. A refusal is reported as one, through whatever
  channel says so, and not as the opposite.
- The two queues fail independently: one throwing does not stop the other in the same pass.
- The failure reporter cannot end the worker, for the same reason every other application callback
  cannot (B-52).

## Findings

**The item's own discriminator would have replaced one lie with another.** It reads
`PetichResult.SystemFailure` as "the engine refused", and `process` returns that for at least four
different things: a chain that changed and ran nothing, a rollback that could not be started
(B-51 leaves the row for the sweeper), retries exhausted — and **a rollback that succeeded**, which
is the sweeper working exactly as intended. Reading the result would have reported an ordinary
rescue-by-rollback as a worker failure and stopped counting it.

**So the engine answers the narrow question instead.** `PetichEngine.chainRefusal(petich): String?`
sits beside `owns` — the other thing the sweeper must know about a saga before it touches it — and is
the same `chainMismatch` the engine runs for itself, counter included. The expiry queue already gets
this fact from the engine as `ExpireResult.ChainChanged`; the stuck queue now gets it too, and both
report it through `onWorkerFailure` with the same shape.

**Asked BEFORE the claim, which the item did not ask for and is the point.** The claim is a write;
writing re-stamps the row; for a saga the engine is going to refuse, that write buys nothing and
costs the row its place in the query. It also makes the "not even claimed" assertion possible, which
is what the test checks.

**Why this one mattered more than its size suggests.** The refused saga is the only outcome that
**repeats for ever**: nothing is written for it on purpose (B-44), so it keeps matching the query
that found it. The counter did not report one wrong number — it reported one per poll, indefinitely,
as "an instance died mid-saga", beside the counter B-44 added to say the opposite.

**`revived` now means "claimed and handed to the engine".** That is what it can mean without a second
read of the row, and it is what its own documentation always said ("picked up after the process that
was running it died"). The case it was actually wrong about — a saga it never picked up — is gone.

**The cancellation test could not move, and was rewritten before it counted.** It cancelled the
worker while it waited out the poll interval, which reaches neither `catch`, so it passed just as
well with `catch (e: CancellationException) { throw e }` deleted. It now cancels the worker while it
is suspended inside a query, and that mutation fails it.

**Checked by four mutations, one per claim.** The refusal check removed → `expected: <0> but was:
<1>`; both queues back in one `try` → `the stranded queue was not swept`; the reporter unguarded →
the reporter's own `IllegalStateException` escapes the worker; the cancellation rethrow removed →
`cancellation was reported as a worker failure: [(sweep, shutting down)]`.

**Verification.** `./gradlew build` on the Linux box, exit code read rather than piped: green,
including `petich-core:linuxX64Test` (182 tests, four more than before) and the conformance corpus
against a real Postgres. `make check` and `code_anchors.py --repos ..` clean. No consumer migration:
every change is additive.

**Found on the way, and not this item's to fix.** shashki wires `SuspendedPetichSweeper` with no
`onWorkerFailure` at all (`Application.kt`), so its sweeper is silent about every failure — which is
the state that callback's own documentation exists to describe. That belongs in shashki's backlog.
