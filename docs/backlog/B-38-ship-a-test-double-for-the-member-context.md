---
id: B-38
title: "Both consumers wrote the same context double, and it broke twice"
status: done
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

## Findings — 2026-09-20

**What is shipped is not a double, and that answers the item's real question.** A second
implementation would have moved the problem rather than removed it: a double can disagree with the
thing it doubles. `PetichMemberProbe` **is** the class the engine runs every member through, lifted
out of `PetichEngine` and given public read-backs — `enrichment`, `record`, `events`, `effects`,
`decision`. A test asserting against it asserts against what production does.

**The disagreement was not hypothetical.** shashki's hand-written context answered `recordedValue()`
from what happened in front of it. The engine's answers `written ?: petich.stepRecords[stepKey]` —
so a record the **saga carries** from an earlier pass, which is the shape every real rollback has,
read back as `null` in the test and as itself in production. A case asserting exactly that is in
`MemberProbeTest`, and the mutation that removes the saga lookup fails it alone.

**`decision` has a vocabulary of its own** and the engine's outcome type stays internal. A consumer
asserting on a refusal should not be reading the type the engine dispatches on — and the probe's
`Suspended(again = …)` says the one thing that matters about a wait: whether the next answer belongs
to this member or to whatever comes after it (B-37).

**Rejected, as the item suspected: defaults on `PetichMemberContext`.** It removes the breakage by
letting a consumer's double silently miss a method that matters — a compile error traded for a test
that passes while asserting nothing. With the context shipped there is no consumer implementation
left for a new method to break, which is the same property obtained without the trade.

**Both consumers' doubles are deleted**, against `0.4.0.81`. konekt's was eleven methods, shashki's
the same eleven written independently.

**A process mistake worth keeping.** The first attempt to take the snapshot pinned `0.4.0.80` because
it was newer than the last one used — and that build predated the merge. The number is not the
content: what settled it was fetching the jar and looking for the class in it. Same family as *влитое
не значит выпущенное*, arriving through a version number rather than a tag.
