---
id: B-44
title: "A saga whose chain changed is refused for ever and has no status for it"
status: done
priority: P1
size: S
stage: stage-10-review
blocked_by: []
---

# B-44 — the second state with no automatic way out, and this one has no name

Refusing a resume whose recorded prefix no longer matches is right, and covering the prefix rather
than the whole chain is what makes appending a step an ordinary release (B-21). What is missing is
what happens to the saga *afterwards*.

Verified rather than assumed:

- `chainMismatch` returns `PetichResult.SystemFailure` and **writes nothing** — the row keeps its
  status, its position and its deadline (`Petich.kt`);
- the expiry path turns the same refusal into `ExpireResult.ChainChanged`, and
  `SuspendedPetichSweeper` hands it to `onWorkerFailure` and moves on (`SuspendedPetichSweeper.kt`);
- so the sweeper re-finds the saga on every pass, re-computes the same mismatch and re-reports it,
  for as long as the deploy stands. The TTL cannot save it either: expiring means rolling back, and
  rolling back walks the chain that is refused.

Whatever it held — a hold, a reservation, a driver — is held for the duration. This is the **second**
state in the engine with no automatic way out, and unlike `COMPENSATION_FAILED` it has no status of
its own, so nothing on a dashboard separates it from a saga that is merely slow.

## Acceptance

- A saga refused for a changed chain is distinguishable from one that is running — by a status of its
  own, or at minimum by a counter that a person can alert on, decided knowingly and recorded.
- If a terminal status is chosen, it announces itself the way an exhausted compensation does: a row in
  the outbox, so the fact leaves the database.
- The sweeper stops re-reporting the same saga every pass, or it is stated why re-reporting is the
  wanted behaviour.
- A runbook paragraph either way: roll the deploy back, let the sagas in flight finish, roll forward.
  That is the recovery whether or not a status is added, and it is nowhere today.

## Findings

**Distinguishable, by a counter rather than a status — and the choice is the finding.**
`onChainRefused(type, phase)` fires on every refusal, on the forward path and through the expiry path
both, because the sweeper reaches the same `chainMismatch`. Nothing is written to the row.

**A terminal status was refused, and the review's own analogy is what refuses it.**
`COMPENSATION_FAILED` marks damage: the saga is half undone and nothing will touch it again. A
refused chain is a *disagreement between two deployed versions* — roll the deploy back and every
refused saga resumes where it stopped and finishes. A terminal status cannot be un-set by the deploy
that fixes the cause, so marking these sagas dead would turn a recoverable situation into an
unrecoverable one. That is not an argument from taste: it is `rolling the deploy back lets the saga
finish because nothing was written`, which is a test in this change.

**The sweeper keeps re-reporting, and that is stated as intent rather than left as behaviour.** The
mismatch is not an event that happened once; it holds while the versions disagree. A signal that went
quiet after the first sweep would read as "resolved" to whoever is watching, which is worse than no
signal. `it is counted again on every pass while the condition holds` pins it, so the next person to
think it is a bug finds the answer in a test.

**The runbook exists now** — three steps in the README beside the fingerprint rule, plus the two ways
a release avoids the case entirely (append rather than insert; or run the old chain beside the new
one until the sagas started under it are done).

**The counter's neighbour is why it is a gap rather than a preference.** `onChainUnavailable` has
existed for the *less* specific failure — a chain that could not be assembled at all — since it was
written. The more specific and more damaging case had nothing.

**Left open as a person's decision, with its price named:** a *non-terminal* queryable status.
`WHERE status = 'REFUSED'` answers "which sagas are stuck" in a way a counter cannot, and self-heals
on the next matching pass. It costs a write on the one path that writes nothing today, a version bump
that can race a healthy process, and a new enum value every consumer's exhaustive `when` must handle.
Recorded in D14 so it is decided rather than defaulted.

**No consumer had to change.** The method is defaulted; the portfolio's only implementation,
shashki's `RefusingMetrics`, overrides one unrelated method and still compiles untouched.

**Verification.** Full `build --rerun-tasks` on the Linux box: 429 tests across `jvmTest`,
`linuxX64Test` and `test`, result-file freshness checked. Mutation after the implementation was
committed: silencing the counter fails the two cases that assert it. Restored, tree clean.
