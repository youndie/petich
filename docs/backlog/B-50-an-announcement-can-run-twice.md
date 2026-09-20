---
id: B-50
title: "An announcement is re-run after a crash and nothing says it must tolerate that"
status: wip
priority: P1
size: M
stage: stage-10-review
blocked_by: []
---

# B-50 — the receipt goes out twice, and the rule that would have said so is written for steps

Verified rather than reasoned:

- an announcement is a member like any other, and the engine commits `currentInterceptorIndex + 1`
  **after** its body returns;
- so a process that dies inside `announce()` leaves the row pointing at that announcement;
- `SuspendedPetichSweeper` re-drives sagas left in `PROCESSING`, and the announcement runs again.

shashki's `PublishSettled` sends a receipt by mail and then emits. A process that dies between the
send and the commit sends the rider a **second receipt** on the next sweep. konekt's two are
`ctx.emit` and nothing else, and the outbox key makes a repeat harmless, which is why nobody has seen
this.

The README's first rule — *"`execute()` must be idempotent"* — says exactly the thing that would have
prevented it, and it is written about `execute`. The fifth — name a remote effect before the call —
is written about a step. An announcement is neither, and B-41 introduced it without revisiting
either. `ctx.idempotencyKey` **is** available to it (`PetichAnnouncementContext` extends
`PetichMemberContext`, where the key and `stepKey` live), so nothing is missing from the type; what
is missing is the sentence saying an announcement needs it for the same reason a step does.

## Acceptance

- The rules in **What it asks of a member** cover an announcement explicitly wherever they cover a
  step — at minimum the idempotence one and the naming one — rather than leaving a reader to infer
  that a member with no `compensate` is also a member that runs once.
- A test in which the process dies inside an announcement and the re-drive does not produce the effect
  twice, written against a member that does I/O rather than one that only emits.
- **shashki's receipt moves with it** — this is the item's acceptance rather than follow-up work. A
  second receipt for one ride is a product-visible defect, and the mail send is the only I/O in an
  announcement anywhere in the portfolio.
