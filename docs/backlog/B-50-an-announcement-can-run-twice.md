---
id: B-50
title: "An announcement is re-run after a crash and nothing says it must tolerate that"
status: done
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

## Findings

**The rules cover an announcement now, where they covered a step.** The first was written about
`execute()`; it is written about a member's body, and carries the sentence the omission needed: a
member with no `compensate` reads as one that runs once and is not one. The naming rule says "a step
or an announcement alike".

**The defect is larger than this item described, and the test is what showed it.** The item said a
process dying inside `announce` leaves the row pointing at it for the sweeper. True, and not the only
way in: `processWithRetry` re-runs the **whole pass** on an optimistic-lock conflict,
`maxProcessAttempts` times — five by default. `AnnouncementRunsAgainTest` measures **six** sends for
one saga: five attempts plus the pass that finally committed. So this is not a crash window; two
requests touching one saga is enough, on healthy instances, which is exactly what the first rule
already said about steps and nobody had said about announcements.

The assertion is `> 1` rather than `== 6`, because six is a property of a config default and the
claim is not about that number.

**The unnamed member is kept beside the named one as a live control** rather than described, and the
mutation confirms which line carries it: dropping `ctx.idempotencyKey` from the named announcement
fails that case alone.

## The third criterion was not met as written, and that is a finding rather than a slip

It read *"shashki's receipt moves with it — this is the item's acceptance rather than follow-up
work"*, written on the belief that the consumer half was a migration. It is not:

- **`ctx.idempotencyKey` does not fix it there.** The rule works when the far side deduplicates, and
  **SMTP does not** — `SmtpReceiptSender` hands a message to a relay, and no caller-chosen name makes
  a mail server collapse two sends.
- **`Settled.RECEIPT` cannot be the guard either**, being written by the same pass that did not
  commit. That is B-43's shape exactly.

What is left is a durable claim written *before* the send, which is a table: a schema change, not a
migration, and one that chooses at-most-once delivery. shashki has in fact already made that choice
elsewhere — `SendReceiptUseCase` swallows a send failure and `Settled.RECEIPT` exists so a missing
receipt can be found — so the direction follows from its own decisions rather than needing a new one.
But the work is its own, and it is filed as `youndie/shashki` **B-92** with both forms, the trade
each accepts, and the acceptance that a test must count **sends** rather than claims.

petich's half is complete: the rules, the measurement, and the control.
