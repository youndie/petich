---
id: B-48
title: "The rule assumes the far side can cancel by the caller's name, and often it cannot"
status: open
priority: P1
size: M
stage: stage-10-review
blocked_by: []
---

# B-48 — an idempotency key and a cancellable handle are not the same thing

B-43's rule ends "cancel whatever is under this key", which assumes the far side can be *addressed*
by the caller's name. For a large and ordinary class of APIs it cannot: the idempotency key is a
**deduplication token** with a bounded lifetime — replay the same request within the window and you
get the original response — and nothing can be cancelled by it.

B-43's own findings already show this, at the port it names: shashki's `PaymentGateway.hold()`
returns a generated `HoldId` and `release()` takes that id. The name the caller chose buys a replay,
not a handle.

The fallback that does work on such an API, and which the rule does not mention:

> In `compensate`, **repeat the `execute` request with the same key**, take the original response —
> which carries the id — and cancel by that id. If the first call never landed, the repeat creates
> the effect and the cancel immediately removes it. Either way the net is zero.

That has a precondition the rule must state, because it is a number and not a principle:

> **The far side has to keep the key longer than the rollback can take.** The ambiguous case only
> arises on the pass that ran `execute`, and a rollback can stretch over at most
> `maxCompensationAttempts × stuckAfter`. The far side's retention window must exceed that product.

It is the same shape of arithmetic the README already publishes for `stuckAfter` itself, and it
belongs beside it.

## Acceptance

- The rule distinguishes the two far sides — one that cancels by the caller's name, one that only
  deduplicates — and gives the replay-then-cancel form for the second.
- The retention inequality is written with both config names in it and placed beside the existing
  `stuckAfter` arithmetic, so the two are read together.
- What petich does when the far side satisfies neither is stated rather than left open: an effect
  that cannot be named and cannot be replayed cannot be reliably undone, and that is a property of
  the integration.
- shashki B-91, which is the live instance of this on `PaymentGateway`, points at whichever form is
  chosen.
