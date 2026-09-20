---
id: B-53
title: "A parked cascade is not undone, and its phase is not remembered"
status: wip
priority: P1
size: M
stage: stage-11-review
blocked_by: []
---

# B-53 — what `resuspendFor` writes down, and the two things it leaves out

**The cascade is not compensated when its TTL expires.** `Suspend` stores
`currentInterceptorIndex = index + 1` — the position past the member — and `Resuspend` stores
`index`, because the next answer belongs to the same member (B-37). `expireClaimed` then takes
`compensatingFromIndex = petich.currentInterceptorIndex` and the rollback starts at `index − 1`. So
the member that offered a ride to a driver and is waiting for the answer **never gets `compensate`**.
`CascadeKeyTest` asserts that such a member withdraws every sub-key it issued; on this path nothing
asks it to.

The complication is real: from one number, "suspended at member k" and "re-suspended at member k+1"
are the same row. The fix is to stop deriving the rollback's starting point and **write it down when
the saga parks** — `compensatingFromIndex` is already a column, it can be filled in the PENDING row
and cleared on resume.

**And `Resuspend` does not store the phase.** `Suspend` writes `currentPhase = phase`; `Resuspend`
writes the index and nothing else, so the phase comes from `latest`. When the re-suspending member is
the **first of its phase** and earlier phases are not empty, the row says the previous phase with
`index = 0`, and every resume re-runs all of that phase's members. For a check that is wasted work.
For a check that itself calls `suspendFor` it is a second one-time code sent to a person.

`ReaskAtTheSameMemberTest` does not catch it because its cascade is the definition's only member —
green by coincidence.

## Acceptance

- A cascade that parks with `resuspendFor` and then expires **is** compensated, and the test asserts
  the sub-keys were withdrawn rather than that the saga ended.
- The rollback's starting point is read from what was written at parking time, not re-derived from an
  index whose meaning depends on which verb wrote it.
- A definition of `validate(...)` then `step(cascade)`: after a resume the validation runs **once**.
- `ReaskAtTheSameMemberTest` grows a member in front of the cascade, so it stops being green by
  coincidence.
