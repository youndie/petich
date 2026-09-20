---
id: B-53
title: "A parked cascade is not undone, and its phase is not remembered"
status: done
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

## Findings

**The rollback's starting point is written at parking time and read at expiry**, in both branches,
so nothing infers it. The number it used to be derived from means "one past this member" when
`suspendFor` wrote it and "this member" when `resuspendFor` did — one field, two meanings, and the
expiry knew only one of them.

**And the change introduced a hazard of its own, which is why forward progress clears it.** A row
that parked carries a starting point; a resume that runs further makes that point wrong, and a stale
one would undo *too little* — the same class of defect pointing the other way. The Proceed branch
clears it, so anything that parks again writes its own. That is not in the acceptance; it is the
price of writing down what used to be derived, and it belongs with the change rather than in a
follow-up.

**The phase was the smaller half and the nastier symptom.** `resuspendFor` never wrote it, so a
re-asking member that is the first of its phase left the row naming the **previous** phase with
`index = 0`, and every resume re-ran all of that phase. For a plain check that is wasted work. For a
check that asks for a one-time code it is a second code sent to a person.

**`ReaskAtTheSameMemberTest` was green by coincidence, and the coincidence is now named in it.** Its
cascade was the definition's only member, and `index = 0` of two different phases is the same member
when there is only one. A `validate` in front makes them different — and the test's expectation grew
a `check` that appears exactly **once**, which is the other half of this item asserted by a test that
was not written for it.

**Checked by two mutations, each isolating one half.** Deriving the start again fails the cascade's
withdrawal alone; taking the phase back out of `Resuspend` fails `ReaskAtTheSameMemberTest` — the
test that could not have caught it an hour ago.

**Verification.** Full `build --rerun-tasks` on the Linux box, exit code read rather than piped: 477
tests across `jvmTest`, `linuxX64Test` and `test`.
