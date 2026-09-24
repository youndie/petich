---
id: B-66
title: "A resume that dies in its first member is later expired without that member"
status: done
priority: P2
size: S
stage: stage-12-tracer
blocked_by: []
---

# B-66 — the one window B-65 left

[B-65](B-65-the-engine-never-writes-processing.md) made every committed step write `PROCESSING`, so a
resumed saga that dies after moving on is found by the stuck queue. **The window before the first
commit of a resume is not covered, and by reading it leaks.** Hypothesis, not yet run:

1. a member suspends; the row is `PENDING_SIGNATURE`, `compensatingFromIndex = index + 1`, with a
   deadline if a TTL is configured;
2. the client resumes; the NEXT member runs, has its effect, and the process dies before its commit;
3. the row is untouched — still `PENDING_SIGNATURE`, the same deadline, the same start;
4. the expiry claims it and rolls back from `compensatingFromIndex - 1`: the member that parked and
   everything before it. **The member that died in the resume is not undone**, although its outcome
   is exactly the unknown one B-18 rolls back.

Without a TTL the row waits for the client, whose retried resume re-runs that member — at-least-once,
which members already owe — so the leak needs a TTL.

- **Reproduce first.** If the expiry does undo it, the item closes with where the reasoning was wrong.
- **The decision, if it reproduces:** a resume writes its start (one write, and `WriteCountTest`'s
  suspending count moves by one, with the README's Cost section), against the expiry widening its
  start by one when the row says a resume was attempted — which needs something on the row that says
  so, and nothing does.

## Acceptance

- The reproduction exists and its outcome is recorded.
- If it leaked: the member is undone by the expiry, and the write count and the README move together.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonTest/kotlin/StrandedMidPassTest.kt`,
  `petich-core/src/commonTest/kotlin/WriteCountTest.kt`

## Findings

**Reproduced as written, and it was half of it.** `ExpiryAfterADeadResumeTest` before the fix:
`[do:hold, do:capture, undo:hold]` — the expiry undid the parked member and kept `capture`, which had
run in the dead resume. **The same stale start is reached without any crash**: a member that *throws*
first after a resume took the parking's `compensatingFromIndex` instead of B-18's "the member that
threw is undone too", and lost its compensation the same way. That second test failed identically
before the fix. It is the ordinary path of a confirm-then-capture saga — konekt's top-up holds and
waits for exactly this — so it was the larger of the two, and it needed no crash to happen.

**One cause: the parking's rollback start outlived the resume.** `suspendFor` writes
`compensatingFromIndex = index + 1` so an expiry undoes the member that parked; nothing replaced it
until the resume's first commit, and `triggerCompensation` reads a non-null value in preference to
its own "the member that threw is included".

**Decided as the item's first option: a resume writes its start.** Before it runs a member, a pass
that reads `PENDING_SIGNATURE` writes `PROCESSING`, clears the deadline and clears the parking's
start. The second option — the expiry widening its start when a resume was attempted — needed
something on the row to say so, which is the same write.

- **What changes for a crash:** the row after a dead resume is `PROCESSING` with no deadline, so the
  expiry no longer touches it (`NotSuspended`) and the stuck queue carries it forward, re-running
  `capture` — the at-least-once every member already owes. The item's acceptance was worded as "the
  member is undone by the expiry"; after this write it is instead carried on, which honours the
  client's confirmation rather than discarding it. The test says so.
- **What changes for a throw:** the rollback now starts where B-18 says, `capture` included.
- **The cost: one write per resume.** `WriteCountTest`'s suspending saga moves from 8 to 9 — edited
  with the reason in its KDoc, and the README's Cost section moved with it in the same change.
- **A side effect worth having:** the write is a claim. A second resume of the same saga loses the
  version and re-reads a row that is no longer waiting; konekt's resume guard refuses it.

**Checked by mutation:** the start write disabled → both `ExpiryAfterADeadResumeTest` cases and
`WriteCountTest`'s suspending case fail (3 of 216); restored, tree read back clean.

**Verification.** On the Linux box: `:petich-core:jvmTest` and `:petich-core:linuxX64Test` green,
result files read — `ExpiryAfterADeadResumeTest` 2/2, `WriteCountTest` 2/2, `StrandedMidPassTest` 3/3
on each target; `./gradlew build` green including the conformance corpus against a real Postgres.
