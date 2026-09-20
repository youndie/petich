---
id: B-47
title: "The idempotency rule and resuspendFor contradict each other"
status: open
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
