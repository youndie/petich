---
id: B-40
title: "A definition can read in one order and run in another"
status: open
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
