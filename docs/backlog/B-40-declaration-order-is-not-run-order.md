---
id: B-40
title: "A definition can read in one order and run in another"
status: done
priority: P1
size: S
stage: stage-10-review
blocked_by: []
---

# B-40 — the builder refuses a check after a step, and nothing else about order

Members run in **phase** order; the builder accepts them in **any** order. So

```kotlin
step("reserve", Reserve(stock))          // EXECUTION
authorize("confirm", AwaitConfirmation()) // AUTHORIZATION — runs FIRST
```

compiles, reads top to bottom, and runs bottom to top. The whole point of this stage is that the
order is written where the saga is read; here it is written and not read.

- **This is not hypothetical.** `SuspendedTtlTest` was written exactly that way while migrating the
  suite (B-33) and failed: the AUTHORIZATION member suspended before the EXECUTION one had run, so
  there was nothing to roll back and the case asserted the opposite. It took a debugging pass to see
  that the file said one order and the engine did another.
- **The existing rule covers a different mistake.** `add()` refuses a check after a step, because a
  check has nothing to undo and refusing there would keep what ran. Nothing refuses a step declared
  above a member of an earlier phase.
- **A type-state builder is not needed for this.** Refusing a phase lower than the previous one, at
  build time, with the two keys in the message, is enough and is one `require`.

## Acceptance

- A definition whose phases do not run in non-decreasing order is refused where it is declared, and
  the message names the member that goes backwards and the one before it.
- Two members of one phase stay legal, in declaration order — that is what the order is for.

## Findings

**Both criteria, in one `require`.** A member whose phase is lower than the one declared above it is
refused at the line that is wrong, with both keys and both phases named. Non-decreasing rather than
increasing, so several members of one phase keep declaration order — pinned by its own test, because
that is the half a careless `>` would break silently.

**The item's premise needed one correction: the existing rule was not a different mistake.** B-40 was
filed saying "the existing rule covers a different mistake — nothing refuses a step declared above a
member of an earlier phase". True of the second half, wrong about the first. B-39 took the step
overload off `authorize`, so every check sits in a phase below every step; "a check after a step" and
"a phase that goes backwards" are now the same sentence, and the older rule had become this one's
special case.

So the new rule **absorbed** it rather than joining it. Two guards over one mistake hide which is
load-bearing, and the suite stays green either way. What was kept is the older rule's *reason* — a
check has nothing to undo, so refusing after an effect keeps what ran (B-20) — because that is the
concrete cost rather than a restatement, and it is appended to the message when the member going
backwards is a check.

**The absorption is proved by mutation, not by reading.** With the implementation committed, the
comparison was made vacuous (`phase >= ENRICHMENT`). Five tests failed: the three new ones **and both
old check-after-step tests**. Had the old rule still been doing the work, those two would have stayed
green. Restored, tree clean.

**Verified against five real definitions, with a positive control.** petich was published to the
Linux box's local repository and both consumers built against it through an init script — konekt's
three sagas and shashki's two, constructed at runtime by their own suites. A green build proves
nothing on its own, so the control: inverting `tariff_change`'s first two members turned konekt red
with the message read end to end —

> `tariff_change declares `catalogue-and-pending` (VALIDATION) after `record-change` (EXECUTION),
> which runs later. Members run in phase order, so this reads in one order and runs in another: move
> `catalogue-and-pending` above `record-change`. A check has nothing to undo, so refusing there would
> keep what ran — if `catalogue-and-pending` is meant to run at that point, it is a PetichStep`

Both consumer trees were restored and are clean; neither needed a change, because both already
declared in phase order.

**Written up as D11**, with the absorption as a correction found while implementing — and D3's claim
that the ordering rule is what keeps a check from sitting after an effect is now the special case of
a general one.
