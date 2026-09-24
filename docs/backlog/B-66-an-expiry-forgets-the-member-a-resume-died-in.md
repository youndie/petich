---
id: B-66
title: "A resume that dies in its first member is later expired without that member"
status: open
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
