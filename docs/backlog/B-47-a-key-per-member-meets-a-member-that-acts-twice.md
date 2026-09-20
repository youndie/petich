---
id: B-47
title: "The idempotency rule and resuspendFor contradict each other"
status: done
priority: P1
size: S
stage: stage-10-review
blocked_by: []
---

# B-47 — a key issued per member, handed to a member the engine built to act many times

B-43's rule says a member whose effect is remote must name that effect before the call, and
`ctx.idempotencyKey` is that name: `"<saga id>:<member key>"`, deliberately the same on every pass.
For one call retried that is exactly right, and a mutation test pins it.

`resuspendFor` describes the other shape, and the engine went out of its way to support it (B-37): a
member that acts **more than once at its own position** — a cascade offering a ride to one driver,
then the next. A reader who follows B-43's rule literally hands the far side `ctx.idempotencyKey`
unchanged on every attempt, and the second offer arrives under the first offer's key. A deduplicating
receiver answers with the first offer's result, and the refusal is silent.

- **shashki's cascade is not broken today, and that is luck rather than design.** Its outbound calls
  are `board.post(Offer(rideId, driverId, …))` and `timeouts.schedule(rideId, driverId, …)`, which
  already carry the driver — so the identity differs per attempt by accident of the port's shape. It
  passes `ctx.idempotencyKey` nowhere.
- **The two features are each documented and contradict each other where they meet.** That is the
  defect: not a bug in either, but a rule that is silent about the one member shape the model makes a
  verb for.

## Acceptance

- The rule says the key is issued **per member, not per call**, and that a member making more than
  one call derives sub-keys with a deterministic discriminator — the driver id, or the attempt number
  the member already keeps in its own record or enriched payload.
- It says what the compensation of such a member owes: **cancel every sub-key it may have issued**,
  not only the last.
- Whether petich offers a helper for the derivation, or only states the rule, is decided and
  recorded. A helper that takes a discriminator is one line; a rule that each consumer spells by hand
  is the thing B-43 argued against when it refused to let a member build the base key itself.
- shashki's cascade is checked against whichever is chosen, and the accident above is written down
  where the cascade is declared so the next port change does not quietly remove it.

## Findings

**The rule now says per member, and gives the per-call form.** `ctx.idempotencyKey(discriminator)`
returns `"<saga id>:<member key>:<discriminator>"`. It is a helper rather than a sentence for the
reason B-43 refused to let a member build the base key: the forward pass and the rollback have to
spell it identically, and that argument is *stronger* here, because there are now N of them.

**The half petich cannot do is stated as the member's debt.** A member that issued several sub-keys
owes cancelling all of them, and which discriminators it used is its own knowledge — so the
discriminator must be derivable again inside the compensation. A random value names a call nobody can
cancel; that is why the rule asks for the candidate's id or the attempt number the member already
keeps.

**The item's own premise about shashki was wrong, and the truth is duller.** It was filed saying the
cascade survives "by accident of the port's shape" — that `board.post(Offer(rideId, driverId, …))`
already carries the driver. True, and not what saves it. `OfferBoard` is an `InMemoryOfferBoard` and
`OfferTimeouts` is a map of timers: neither is remote, neither even suspends, and **the rule does not
reach that cascade at all**, being about naming a remote effect. A near miss would have been a better
story than the truth, which is the reason to check.

So the fourth criterion is met differently than written: there is no accident to record. What is
recorded, at the call rather than in a document, is why the rule does not apply and exactly what
would change the day either port leaves the process — `youndie/shashki`,
`docs/cascade-effects-are-in-process`.

**The defect is kept as a live control rather than described.** `CascadeKeyTest` models the far side
that matters — one that deduplicates — and runs the same three-candidate cascade twice. On the plain
key, `a cascade on the plain key offers the second ride to the first driver`: two candidates asked,
neither reached, the board still showing the first, every call having returned something plausible.
On the discriminated key, all three. Two more cover the rollback: withdrawing only the last sub-key
leaves the rest outstanding, withdrawing all of them leaves nothing.

**Checked by mutation after the implementation was committed:** making the discriminator do nothing —
`idempotencyKey(discriminator) = idempotencyKey` — fails three of the five. Restored, tree clean.

**Verification.** Full `build --rerun-tasks` on the Linux box: 439 tests across `jvmTest`,
`linuxX64Test` and `test`, result-file freshness checked. shashki built green with the note.
