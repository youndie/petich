---
id: B-49
title: "A failed announcement leaves a counter and nothing else"
status: done
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

## Findings

**A seam, not an event of petich's own**, and the argument is `exhausted`'s unchanged: petich does not
know what an unannounced saga means to the system it lives in. `AnnouncementFailureHandler.failed`
returns outbox events and is defaulted to nothing, so no existing wiring changes.

**The implementation is smaller than this item assumed, and that is worth recording.** It proposed
attaching the event to "the same final commit that completes the saga". That write is
`repository.update(completed)` — the overload carrying no events — so the proposal meant routing it
through `updatePetich` and touching the one status write that deliberately bypasses
`forceUpdateStateWithRetry`. None of it is needed: **an announcement that throws still proceeds**, and
the Proceed branch already commits through the outbox-aware path. Emitting through the member's own
context puts the fact in the write the member was making anyway — one transaction, no extra write.

**The third criterion answered itself.** It asked whether the event should be suppressed when the
repository cannot store an outbox, or dropped and counted. Neither is decided here: riding the normal
path means `onDroppedEvents` counts it and `requireOutbox` refuses that wiring at construction,
exactly as for any other event. Inventing a rule for this one would have been the mistake.

**The test separates the case the counter cannot describe.** `an announcement that dies before
emitting still leaves a row in the outbox` is the acceptance; a member that throws *after* emitting
proves nothing here, because what it asked for is committed either way (B-41) — so that case is a
second test asserting **both** rows, in the order they happened. Six cases in all, including the
no-handler default and the repository that cannot store events.

**Checked by mutation after the implementation was committed:** removing the emission fails four of
the six. Restored, tree clean.

**Verification.** Full `build --rerun-tasks` on the Linux box, exit code read rather than piped: 461
tests across `jvmTest`, `linuxX64Test` and `test`. The README's examples were compiled against a local
publication of this change through B-46's new probe, which is the first item to have that check
available.
