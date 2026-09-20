---
id: B-56
title: "D14 says nothing is written, and on the stuck queue something is"
status: done
priority: P3
size: S
stage: stage-11-review
blocked_by: []
---

# B-56 — the text, not the behaviour

D14 and the README's runbook say a saga refused for a changed chain is **left exactly as it was** and
that the counter therefore fires on every pass. On the forward path that is true. On the stranded
queue it is not: `sweepStuck` takes its claim — one write that bumps the version and re-stamps
`updated_at` — **before** `chainMismatch` refuses. So the row moves, and it stops matching the "not
touched since" predicate that found it, which means the refusal is reported about once per
`stuckAfter` rather than once per pass.

The behaviour is harmless and arguably better: a row that hides itself for a while is a row that does
not fill a log. What is wrong is the two sentences that describe it, and they are the ones a person
reads while deciding whether an alert is firing as often as it should.

## Acceptance

- D14 and the README say what actually happens on each of the two paths, including the rate.
- If the once-per-`stuckAfter` rate is the wanted one, it is stated as intent rather than left as a
  consequence of where the claim sits.

## Findings

**The defect this item describes was fixed by B-55, one item earlier, for a different reason.**
`sweepStuck` now asks `engine.chainRefusal(petich)` **before** taking the claim, so a refused saga is
not written and does not hide itself for a lease. B-55 moved it because a refusal was being counted
as a rescue; that the rate stopped being once per `stuckAfter` was a side effect nobody wrote down.
Verified by reading the code before touching the text, and then by reproducing the old behaviour:
putting the claim back in front of the check gives **three polls, one report** —

```
expected: <[stuck:p-refused, stuck:p-refused, stuck:p-refused]> but was: <[stuck:p-refused]>
```

— which is this item's paragraph, as a failing test.

**So the second acceptance bullet is moot and the first got bigger.** There is no once-per-
`stuckAfter` rate left to state as intent. What was actually missing is that D14 and the README say
"every pass" without ever saying what a pass is, and there are **three** of them with three different
clocks: `process` when the application calls it, and each of the two sweeps once per `pollInterval`.
The number an operator reads settles at `refused sagas × 2 / pollInterval` with both sweeps on, which
is what you need before putting a threshold on it. Both documents now carry the table and the advice
that follows from it: alert on the rate being non-zero for longer than a deploy takes, not on its
height.

**And D14 was accurate about two paths out of three from the day it was written.** Not wrong by
drift — wrong when written, because it was written about `process` and then read as being about the
engine.

**The correction is held by tests, not by the paragraph.** This is a property of an ORDER — ask
before you claim — and an order is exactly what a later edit reverses without noticing. One witness
per path, each checked by mutation:

| path | what holds it | what the mutation did |
|---|---|---|
| `process` | `RefusedChainIsVisibleTest`, "counted again on every pass" | already existed |
| `expireSuspended` | `RefusedChainIsVisibleTest`, "an expiry over a changed chain is refused and rolls nothing back" | **did not exist** |
| `sweepStuck` | `SweeperReportsWhatHappenedTest`, "reported again on the next poll" | `[three] but was: [one]` |

**The middle one is the finding inside the finding.** The expiry queue reaches `chainMismatch`
through `expireSuspended`, and **no test asserted on it at all** — on the path the engine's own
comment calls the one where this matters most: an expiry rolls a saga back with nobody watching, and
a rollback walking a changed chain compensates steps that never ran. Removing the check makes the
test report `expected a refusal: Expired(petichId=p-1)` — the saga rolled back over a chain that had
moved, which is the damage, not the inconvenience.

**Verification.** `./gradlew build` on the Linux box, exit code read rather than piped: green,
including `petich-core:linuxX64Test` (185 tests, three more than before) and the conformance corpus
against a real Postgres. `make check` and `code_anchors.py --repos ..` clean. No code changed: two
documents and two tests.
