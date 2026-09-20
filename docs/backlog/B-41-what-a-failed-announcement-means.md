---
id: B-41
title: "announce takes a full step, and its failure has no defensible meaning"
status: wip
priority: P2
size: M
stage: stage-10-review
blocked_by: []
---

# B-41 — by the time it announces, the work is done

`announce(key, step: PetichStep<P>)` takes a member that can `fail`, `reject`, `suspendFor` and be
compensated. By the time it runs the stock is reserved and the money is captured. Rolling the saga
back because a notification did not go is almost certainly the wrong answer, and the type offers it
as the natural one.

With a transactional outbox the question should not arise: an announcement records an intent in the
same write as the final state, so it can only fail with that commit. A type of its own — returning
the event rather than taking a context — would say that a notification cannot be undone and cannot
fail the saga.

- **The portfolio has one member that would not fit as stated, and it is the interesting one.**
  shashki's `PublishSettledStep` sends a receipt by mail before emitting. It is I/O in an
  announcement, and it is deliberate: the send's failure is swallowed by hand and the outcome written
  into the enriched payload, because `SendReceiptUseCase` says outright that a settlement rolled back
  over a mail server would be the tail wagging the dog. So the rule the reviewer proposes is already
  the rule that member follows — **by discipline, not by type**, which is the same gap as B-39.
- **Which means the design question is not "can an announcement do I/O" but "can it fail the saga".**
  A type returning only an event answers both at once and is too strong for shashki; a type that may
  act but has no `fail`, no `reject` and no `compensate` answers the one that matters.
- konekt's two announcing members are `ctx.emit` and nothing else, so they fit either shape.

## Acceptance

- An announcing member cannot fail the saga, refuse it, or be compensated — checked by the type.
- shashki's receipt keeps working without swallowing anything by hand, or the reason it must is
  stated where its member is declared.
- Whether such a member may suspend is answered too: nothing in the portfolio does, and "no" is
  cheaper to relax later than to impose.
