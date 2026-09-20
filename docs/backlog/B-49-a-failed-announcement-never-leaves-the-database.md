---
id: B-49
title: "A failed announcement leaves a counter and nothing else"
status: open
priority: P2
size: S
stage: stage-10-review
blocked_by: []
---

# B-49 — the one fact the outbox exists to carry, and it is the one that stays behind

B-41 made an announcement's exception count rather than roll the saga back, which is right. What is
left is that `onAnnouncementFailed` is **all** that is left. If the exception happened before
`ctx.emit`, there is no event, the saga completes, the state is correct, and the consumer at the
other end learns nothing — not late, never.

This repository has already made the opposite argument twice, in the same words:

- `requireOutbox` refuses at wiring time rather than counting at runtime, because "the write
  succeeds, the saga completes, its state is correct, and every assertion anybody naturally makes
  about that run passes";
- `CompensationFailureHandler.exhausted` exists so that a rollback giving up **leaves the database**,
  committed in the same transaction as the status, "since the announcement matters precisely when the
  process is unreliable".

A failed announcement is the same shape and has neither.

The continuation those two suggest: the engine writes its own row — saga id, member key, the reason —
into the outbox in the **same final commit** that completes the saga. It costs no extra write, and it
turns "which orders were never announced" from a question about a graph into a query.

## Acceptance

- A failed announcement leaves a row in the outbox, committed with the write that completes the saga.
- Its shape is decided the way `exhausted` decided the same question: petich does not invent a wire
  format for somebody else's relay, so either a handler seam returns the event, or the event is
  petich's own and documented as such — chosen and recorded, not defaulted.
- It does not fire when `requireOutbox` is false and the repository cannot store events, or it fires
  and is dropped and counted, which is worse. Decide which.
- A test where the announcement throws **before** `emit` and the fact still leaves the database. A
  test where it throws after `emit` proves nothing here.
