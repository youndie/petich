---
id: B-51
title: "A storage error after an effect writes FAILED and undoes nothing"
status: done
priority: P0
size: M
stage: stage-11-review
blocked_by: []
---

# B-51 — the one terminal status nothing will ever pick up, holding money

`doProcess` ends with `catch (e: Exception) { return failTerminally(...) }`, and `failTerminally`
writes `FAILED` and returns. **It compensates nothing.** Everything thrown outside a member's own call
lands there: a transient storage fault on the Proceed write, the Suspend write, a metrics
implementation that throws.

```
hold-funds commits.  reserve runs.  the UPDATE that records reserve's progress throws once.
        → FAILED, the hold standing, the reservation standing.
```

`FAILED` is terminal, so `SuspendedPetichSweeper`'s stranded queue — which looks at `PROCESSING` and
`COMPENSATING` — never touches it. Nothing in the system will ever release that hold. A repeated
request is told the saga already failed, and the README tells a reader that `FAILED` means what ran
was undone.

**The method is older than the sweeper**, and that is the whole explanation. Its reason was "do not
leave a saga in an intermediate status, nobody will pick it up"; since B-26 somebody will.

## Acceptance

- A fault after at least one acting member has committed does **not** end as `FAILED` with no
  compensation. Either `triggerCompensation(stepOutcomeUnknown = true)` runs, or the saga is left in
  `PROCESSING` and the caller gets `SystemFailure` — decided and recorded, not both.
- `FAILED` written without a rollback stays legal **only before the first effect**, and the code says
  which branch it is in rather than leaving a reader to infer it.
- A test where the repository throws a plain `RuntimeException` on the second step's write:
  compensations run, or the status is still `PROCESSING`. Today it is `FAILED` with zero
  compensations, and that is the control.
- The README's account of `FAILED` is corrected if the chosen answer changes it.

## Findings

**It rolls back, and the answer to "was there an effect" is that the question does not need asking.**
The acceptance offered two branches and expected a decision between them; the decision turned out to
be that the predicate is unnecessary. **Only a step has a `compensate`** — a check has none and an
announcement has none — so a rollback over a saga that has run nothing but checks walks members with
nothing to undo and does nothing. A test of "has an effect happened" would be a second thing to keep
in step with the phase model, earning no behaviour of its own. `unwind` says that where it is.

`stepOutcomeUnknown = true`, because the throw arrives from the write that records a member's
progress — the member *ran*, its effect landed, and only the bookkeeping failed. That is B-18's
ambiguity arriving through a different door, and the flag it already has is the right one.

**The second branch survives as a degradation rather than as a choice.** Where the rollback cannot
even be started — and the fault that brought us here is very often the store itself — the row is left
where it is and the caller is told so in the message. `PROCESSING` is what the sweeper re-drives;
`FAILED` written by a process that could not reach the store is what nobody re-drives. When the
choice is between a recoverable lie and an unrecoverable one, neither is written.

**`failTerminally` keeps exactly one caller**, and it is the one where the argument still holds: a
saga no definition applies to has run nothing and has nothing to undo. Both comments now say which
case they are, so the next reader does not have to derive it.

**The reason the defect existed is worth keeping.** `failTerminally` predates the sweeper, and its
own comment gives its motive: do not leave a saga in an intermediate status, nobody will pick it up.
B-26 made that false and inverted it — the intermediate status became the recoverable one and the
terminal one became the trap — and nothing went back to re-read the older decision. That is the shape
this backlog keeps finding: a justification that expired while the sentence it produced stayed.

**Three tests, and the first is the acceptance's control.** A storage fault on the second step's
write undoes **both** steps and still ends `FAILED`; a store that stays broken leaves the row
un-terminal and says so; a saga that has run only checks ends without undoing anything, which is the
test that the missing predicate is missing on purpose.

**Checked by mutation after the implementation was committed:** putting `failTerminally` back on that
catch fails the first two. Restored, tree clean.

**Verification.** Full `build --rerun-tasks` on the Linux box, exit code read rather than piped: 467
tests across `jvmTest`, `linuxX64Test` and `test`. The README now says that the engine's own faults
obey the same rule as `ctx.fail`, and names the one case that legitimately does not.
