---
id: B-36
title: "A member cannot name itself, and observability was hanging off the name"
status: open
priority: P1
size: S
stage: stage-9-definition
blocked_by: []
---

# B-36 — the address moved out of the member and nothing gave it back

`PetichMemberContext` offers `petich`, `enrich`, `record`, `recordedValue`, `suspendFor` and
`reject`. It does not offer the member's **key**, and a `PetichStep` no longer carries a `phase` —
both moved into the definition, which is the point of the stage. What moved with them is the
member's ability to say where it is.

- **Found by migrating shashki (B-32), where it is load-bearing rather than cosmetic.** Every
  settlement step runs inside a tracing span whose name its base class builds as
  `saga.settlement.$phase.${this::class.simpleName}`, and a test asserts that string — because the
  first version shipped a name with the dollar sign still in it to the collector, an unexpanded
  template invisible to the compiler and to every other test. An interceptor knew its own phase.
  A step does not, and cannot ask.
- **The engine has the key the whole time.** `RecordingContext` is constructed with it — it is how
  `recordedValue()` finds this member's record and how the fingerprint is built. It is private.
  Nothing has to be computed or threaded; it has to be exposed.
- **`ctx.petich.currentPhase` covers the phase and only the phase.** It is the saga's position rather
  than the member's address, which is the same thing on the forward path and not during a rollback.
  A compensation reading it gets the phase the rollback has reached, not the phase the member it is
  undoing ran in.
- **Rejected before it is proposed: passing the key to the member's constructor.** That is the key
  written twice — once where the definition declares it and once where the class is built — and the
  copy that drifts is the one in the saga's row, which is the identity `stepRecords` is keyed by.

## Acceptance

- A member can read its own key from its context, in `execute`, in `check` and in `compensate`.
- Whether a member can read the phase it was declared in is answered too, with the rollback case
  named — `ctx.petich.currentPhase` is not that answer.
- A test that fails if a compensation's context reports a different key than the forward pass did.
- shashki's span names survive the migration without the phase being spelled twice (B-32).
