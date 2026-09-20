---
id: B-38
title: "Both consumers wrote the same context double, and it broke twice"
status: open
priority: P2
size: S
stage: stage-9-definition
blocked_by: []
---

# B-38 — eleven methods of boilerplate, written twice, by hand

A member takes a `PetichStepContext`. A test that calls `compensate` directly — to ask a member the
question the engine is about to ask it — has to supply one, and there is nothing to supply. So both
consumers wrote the same anonymous implementation of all eleven methods.

- **It is not a convenience item; it is a compile error with a delay.** konekt's double broke on
  `stepKey` (B-36) and again on `resuspendFor` (B-37), each time when the consumer took a new
  snapshot — not when the method was added. A library that grows an interface grows every consumer's
  test double with it, silently, until the next bump.
- **Two consumers, written independently, identical in shape.** konekt's is in `TopUpSagaTest`,
  shashki's in `SettlementSagaTest`, both recording what was recorded and swallowing the rest.
- **What to ship is not obvious and is the item's real question.** A `FakePetichStepContext` in a
  `petich-testing` module is one answer; a `@TestOnly` factory in core is another; and an
  interface with defaults would remove the breakage at the cost of letting a consumer silently miss
  a method that matters. The third is how this problem is usually solved and is probably wrong here.
- **The direct-`compensate` test is worth keeping**, which is why this is not "stop doing that". It
  is how konekt pins that a tip's rollback does not refund the fare and how shashki pins the same
  shape — asking one member one question, with no saga, no engine and no database.

## Acceptance

- A consumer can obtain a working `PetichStepContext` from petich without writing one.
- It records what a member did to it — the emitted events, the attached effects, the record, the
  enrichment, the decision — so a test can assert on them rather than on side effects alone.
- Adding a method to `PetichMemberContext` does not break a consumer's compile in a later release
  without the release notes saying so; whichever shape is chosen, that property is stated.
- konekt's and shashki's hand-written doubles are deleted.
