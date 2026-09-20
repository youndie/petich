---
id: B-48
title: "The rule assumes the far side can cancel by the caller's name, and often it cannot"
status: done
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

## Findings

**The rule now names two far sides and gives code for each.** One cancels by the caller's name and
the compensation says the name. One only deduplicates, and the compensation replays `execute`'s
request under the same key and cancels by the id that comes back — answered by the first call if it
landed, creating and removing the effect if it did not. Net zero in both, which is what the missing
record could not tell us, at the price of one extra call on the rollback path.

**The retention inequality is written as arithmetic and placed beside the one it belongs with.**
`keyRetention > maxCompensationAttempts × stuckAfter`, because each compensation attempt waits a full
sweep before the next, and the ambiguous case only arises on the pass that ran `execute`. The
`stuckAfter` formula further down now points back at it, since raising `stuckAfter` lengthens what
you are asking of **somebody else's** system as well as of this one — which is not obvious from
either place alone.

**The inequality is a live case rather than a warning.** `a key the far side has forgotten turns the
replay into a second effect` closes the window between `execute` and `compensate` and asserts what is
left: a second hold taken and released, the first still standing. A warning about an inequality is
the kind of thing a reader believes and never checks; this one fails if the arithmetic stops being
true.

**What petich does when the far side offers neither is said out loud** — `compensate` is still
called, and what it can do is bounded by the integration rather than by the engine. That is the one
place in these five rules where the honest answer is "this is not ours", and saying so is better than
a rule nobody can satisfy.

**shashki B-91 now points at the form its port actually needs.** `PaymentGateway.hold()` returns a
generated `HoldId` and `release()` takes it, so the caller's name buys a replay and not a handle: the
second form. Its acceptance also gained the part that is shashki's rather than petich's — name the
gateway's real key-retention window and check it against `maxCompensationAttempts × stuckAfter` for
that configuration, instead of assuming one.

**Checked by mutation after the implementation was committed:** removing the replay from the
compensation fails all three cases. Restored, tree clean.

**Verification.** Full `build --rerun-tasks` on the Linux box: 445 tests across `jvmTest`,
`linuxX64Test` and `test`, result-file freshness checked. No engine change — the rule and its
counter-example are the deliverable, which is why the mutation targets the pattern the tests hold
rather than a line in the engine.
