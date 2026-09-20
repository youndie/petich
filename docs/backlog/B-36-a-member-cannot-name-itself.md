---
id: B-36
title: "A member cannot name itself, and observability was hanging off the name"
status: done
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

## Findings — 2026-09-20

**`PetichMemberContext.stepKey`, and it was a private field rather than a new fact.** The engine
constructs the context with the key and uses it for `recordedValue()` and the fingerprint; exposing
it changed one modifier.

**The phase is deliberately not exposed** (research D10). It was the thing shashki used, so the
obvious repair is to hand it back — but the phase was the member's *bucket* and the class name was
doing the identifying. The key is the address: unique within the definition by construction, and
`saga.settlement.${ctx.stepKey}` is shorter and more precise than the phase-plus-class-name it
replaces. Re-exposing `phase` would put half the definition back inside the member, which is D1
undone for one string.

**`petich.currentPhase` is not that answer, and the way it fails is the reason this needed a
decision.** It is the saga's position: equal to the member's declared phase on the forward pass, and
during a rollback wherever the rollback has reached. A compensation naming a span from it names the
wrong thing in the one direction where the name matters — and looks right in every test that only
walks forward.

**Two cases, and the second is the one that matters.** *every kind of member reads the key its
definition declares* covers a check and a step; *a compensation reads the same key its forward pass
did* runs two members, fails the second, and asserts the undo names `reserve-stock` — not the member
that failed, which reported its outcome and so is not compensated, and not the saga's position.
