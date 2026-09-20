---
id: research-petich-dsl
title: petich — the step and definition model, redesigned
type: research
status: active
date: 2026-09-19
---

# Research: what the engine asks an author to know

petich describes a multi-step operation as a list of interceptors, each declaring a `phase`, a
`priority`, and a `supports(payload)` predicate. The engine filters that list by payload, sorts it by
priority, and walks it. Nothing anywhere names the flow of one saga: to read it, a person collects
every interceptor whose `supports` accepts the payload and sorts them in their head.

This document asks what that model costs, what a definition-first model would replace it with, and
which of the two halves of the answer are already decided by evidence rather than by taste. It
records **verified facts** — counted in this repository and in the two services built on it, on
2026-09-19 — **decisions** with the alternative each rejects, and **risks**. Anything not verified is
called a hypothesis and says where it will be settled.

**The consumers are ours.** konekt and shashki exist to exercise the portfolio's own technology, so
migrating them is not a cost to be minimised — it is the acceptance test for this work, and the one
that finds what a library's own suite cannot.

---

## 1. Verified facts

### 1.1 Half the compensations written against this engine do nothing

Counted by parsing every `override suspend fun compensate` in the two consumers' main sources.

| Fact | Where verified |
|---|---|
| konekt: 20 compensations, **10 of them empty** (`= Unit` or `{}`) | `PurchaseInterceptors.kt`, `TariffInterceptors.kt`, `TopUpInterceptors.kt` |
| shashki: 7 compensations, **2 of them empty** | `OrderSteps.kt`, `SettlementSteps.kt` |
| both: **12 of 27**, 44% | — |

**Consequence 1.** Nearly half of what the contract demands is written to satisfy the type and not to
undo anything. Those steps are validations and enrichments — they refuse or they enrich, and they
have nothing to reverse. One interface is being used for two roles, and the smaller role pays for it
in every implementation.

### 1.2 The choice between refusing and rolling back depends on knowledge the author does not have

`InterceptorResult.Reject` ends the saga without compensating; `Compensate` rolls back. Choosing
correctly requires knowing whether an **earlier** step had an effect — a fact about other people's
steps. An interceptor declares a phase and a `supports()`; the chain it lands in is assembled
elsewhere.

| Fact | Where verified |
|---|---|
| every `Reject` in both consumers sits before any step with an effect — 8 in konekt, 6 in shashki | `B-20`'s closing notes |
| the engine had to be changed so that `Reject` rolls back, because the author could not be asked to decide | `B-20`, merged as youndie/petich#61 |

**Consequence 2.** The fix that shipped makes the safe thing the only thing at runtime. A model in
which the two roles are different types makes it unrepresentable, which is cheaper than a rule.

### 1.3 A saga's position is an index into a list assembled at runtime

`currentInterceptorIndex` is a position in the filtered, sorted list — recomputed on every pass. The
row stores no name, no key, no identity.

| Fact | Where verified |
|---|---|
| a deploy adding, removing or re-prioritising a step in the same or an earlier phase re-points every suspended saga at a different step | `B-21`, merged as youndie/petich#62 |
| the guard that closes it is a fingerprint of the executed prefix, plus a nullable column | `Petich.chainFingerprint`, `prefixFingerprint` |
| ties in `priority` were resolved by the order a dependency container assembled the list in | `B-21`; now broken by `stepKey` |

**Consequence 3.** `stepKey` was added as the fingerprint's input and defaulted to the class name.
In a definition-first model the key is declared at the call site and is the step's identity, so the
fingerprint compares names rather than positions and the whole guard becomes a comparison nobody has
to maintain.

### 1.4 What a step did reaches its compensation through a shared, untyped channel

`compensate` receives the payload and the saga. Anything the action produced — a reservation id, a
charge id — travels through `enrichedPayload`, a merged map shared by every step of the saga.

| Fact | Where verified |
|---|---|
| shashki records a charge id as `enriched(CHARGE_ID)` and reads it back in the compensation | `SettlementSteps.kt`, `CaptureStep` |
| that compensation falls back to `payload.holdId` when the id is absent, and on a tip that is the fare's already-captured hold | youndie/shashki#13 |
| konekt's equivalent guards read the **ledger**, not the saga: `recorded(orderId, HOLD)` | `ExposedPurchaseRepositories.kt`, `release` and `debit` |

**Consequence 4.** "The step did not happen" and "the step happened and produced nothing" are the
same observation in a shared map, and the one live money-shaped defect found this week is exactly
that confusion. konekt answers it by asking its own ledger, which works and is per-application
machinery for a question the engine creates.

### 1.5 The application is told which engine owns which saga

`SuspendedPetichSweeper` takes `engineFor: (Petich) -> PetichEngine?` because an application keeps
several engines over one store, each with its own interceptor list. A saga whose type is not
registered is skipped and reported through `onUnowned`.

**Consequence 5.** There is no value in the library that says "this is what an `order` saga is", so
the mapping from `Petich.type` to its steps lives in the application. `Petich.type` already carries
the identity; only the definition is missing.

---

## 2. Decisions

### D1. The unit of a step stays a class; everything that is not the step leaves it

Decision: `PetichStep<P>` keeps `execute` and `compensate` and loses `phase`, `priority` and
`supports`.

Why:

- a step has dependencies, is tested in isolation, and the pair "do / undo" belongs in one type —
  none of that is what the three removed members are for;
- `supports` is the only reason the engine casts `payload as T` unchecked, and the only reason
  `withPayloadDiagnostics` exists to rename the resulting `ClassCastException`. Naming the payload
  once, on the definition, deletes both;
- `priority` is a number that behaves like `z-index` as it grows, and 1.3 shows what its ties cost;
- the price: a step can no longer be written to apply to two payload types at once. Nothing in
  either consumer does that.

### D2. Two types, not one, and the second cannot roll anything back

Decision: `PetichCheck<P>` returns proceed / reject / suspend and **has no `compensate`**.
`PetichStep<P>` returns proceed / suspend / fail, and fail always rolls back.

Why:

- 1.1: 44% of compensations exist only to satisfy the type;
- 1.2: refusing without rolling back after an effect becomes unrepresentable rather than guarded;
- the price: a step that both acts and refuses must be a `PetichStep`.

**Correction found while implementing B-28.** The line above said such a step "fails", and that is
wrong. konekt's `HoldFundsInterceptor` holds a subscriber's money, **refuses on business grounds if
the hold does not move a row**, records the decline, creates a pending entitlement and then suspends
— one member, deliberately. Under the sentence as written it could only `fail`, which rolls back and
ends `FAILED`: a subscriber told the server broke when they were told they had insufficient funds.

So **`reject` belongs to both contexts, and `fail` only to a step.** What the split actually buys is
not the right to refuse — it is that a check has **no `compensate`**, and therefore cannot be placed
where a refusal would have to undo something. The builder's ordering rule is what enforces that, and
[D3](#d3-the-verb-places-the-step-the-type-names-its-role-they-are-different-axes) is where it lives.
B-20's separation holds underneath: both refusals roll back what ran, and they differ in the name the
saga ends under.

### D3. The verb places the step; the type names its role. They are different axes

Decision: `enrich`, `validate`, `authorize` and `step` place a member in a phase. `authorize` accepts
**both** types.

Why:

- konekt's `HoldFundsInterceptor` holds money and then suspends, as one step, on a recorded decision
  of its own (D5 there). Under a model where the verb implied the type it would have to be cut in
  two to satisfy the model, and its argument for being one is sound;
- the builder forbids `validate` after `step`, and that ordering rule — not the verb — is what keeps
  a check from sitting after an effect. **Without it the trap of 1.2 returns through the back door.**

**Correction — D3 was decided on the wrong axis, and the README's own example is what showed it
(B-39).** A reviewer asked whether `authorize("hold-funds", HoldFunds(payments))` compiles. It did.
So a phase whose meaning is "before effects" accepted members that have them, and that meaning became
a sentence the author remembers rather than a property the type carries — which is the defect this
model removed from an empty `compensate`, arriving at the phases instead.

**The term collides, and that is the cause.** In payments, *authorization* IS the hold. In these
phases, AUTHORIZATION asks whether it is allowed and whether the human agreed. D3 heard konekt say
"our authorisation holds money and waits" and concluded the verb had to take both kinds; what konekt
was saying is that its hold is not an authorisation in this sense at all.

**What was NOT the problem, and the review's own premise had it the other way:** a refusal from a
later member does not leave the hold standing. B-20 made `Reject` roll back what ran, and
`RejectRollsBackTest` holds it. The cost was never money; it was that a reader could not use a phase.

`authorize` takes a check only. A member that acts goes in `step`, above what depends on it — which
also gives the rollback the order it should have had: released before unheld. **Both consumers'
holds moved with it and both still suspend from `step`**, so waiting was never the property this
phase guarded. D9's argument is untouched: a global is still a check, for its own reason.

**A phase carries even less than the correction above assumes, and moving the consumers is what
surfaced it.** Two of shashki's tests reconstruct a dead process by hand and assert that the next one
does not take the money twice; with the hold in AUTHORIZATION they read as proof that a *committed
phase boundary* protects a member that is not idempotent. It does not, and never did. The engine
writes nothing when a phase ends — the phase loop's `copy(currentPhase = …)` is in memory only — and
commits `currentInterceptorIndex = index + 1` after **every** member that proceeds. So the row a
death leaves names a member, and the phase in it is simply whichever phase that member belonged to.

That is worth keeping in view because it cuts both ways. It is why the move costs nothing
operationally: both fixtures needed one number changed, from "the start of EXECUTION" to "the member
after the one that took the money", and the guarantee they test is the same guarantee expressed in
the units that actually carry it. It is also why the phase must mean something *statically*: it is a
promise about what a reader may assume of the code, and it was never a promise about what the
database holds. A design that leans on a phase at runtime is leaning on the wrong thing — which is
the second time in this document that the phase turned out to be documentation wearing a type's
clothes.

### D4. What a step did is recorded per step, not in the shared payload

Decision: `ctx.record(value)` / `ctx.recorded<T>()`, persisted beside the step's key and readable
only by that step's own compensation.

Why:

- 1.4: the one live defect in a consumer is a shared, untyped channel unable to distinguish "absent"
  from "something else". `ctx.recorded() ?: return` makes the guard structural, where konekt writes
  it by hand against its ledger and shashki does not write it at all;
- it is what makes [B-18](../backlog/B-18-the-failed-step-compensates-nothing.md)'s contract — a
  compensation may be called for a step that did not happen — safe by construction rather than by
  documentation;
- **rejected: `PetichStep<P, R>` with the result in the type.** It spreads a type parameter across
  every step that has no result, and it makes the storage shape a function of the step's type. A
  record keyed by the step key is one JSON value in one place;
- `enrichedPayload` stays, for what it is actually good at: data the saga carries **forward**. The
  two channels differ in lifetime and in who reads them;
- the price: a column or a JSON field per saga, and a serializer the application registers. Storage
  changes are not free here — see [B-24](../backlog/B-24-a-release-that-adds-a-column-names-it-nowhere.md).

**Sharpened while implementing B-29.** The decision says the record is committed with the position
the member advances to, which is right and not enough: **the case that matters most is the member
that records and then does not return.** A step that takes the effect, writes down what it did and
then throws is the ambiguous failure B-18 exists for — its own compensation is called, and a record
read only after `execute` returns is a record that is never there when it is most needed. It is read
in a `finally` and folded in by the failure paths as well as the ordinary one.

### D5. A definition is a value, and the engine keeps a registry of them by type

Decision: `petichDefinition<P>("order") { … }` returns a `PetichDefinition<P>`; the engine holds definitions
keyed by `type`.

Why:

- 1.5: `Petich.type` already carries the identity and the definition was the missing half;
- it removes `engineFor` from the sweeper and the timer sink: "which engine owns this saga" stops
  being a lambda an application must keep in step with its own types, and a forgotten registration
  stops being a class of silent expiry;
- naming matters and is not free: `Petich` is the **instance** — a row with an id, a status and a
  version. `PetichDefinition` is what a builder returns. A function `petich(…)` beside a class
  `Petich` is legal Kotlin and would read badly if the result were not named for what it is.

**Correction found while implementing B-42, and the sentence above is the one that was not
followed.** The builder shipped as `petich(…)` — the exact shape this bullet calls out, with the
result not named for what it is. The cost arrived where the bullet predicted and was paid without the
prediction being reread: migrating the suite off the interceptor model meant renaming a private
`fun petich(id: String): Petich` in **thirteen** test files, each of which shadowed the builder the
moment its file needed both. Three such helpers survive in modules whose tests never needed a
definition, which is why the collision looked smaller than it is.

It is `petichDefinition(…)` now. The case for renaming rather than restating D6 comes off the code
rather than off taste:

- **D6's reason is scoped to types** — "every type in the library is already `Petich*`, and a lone
  `SagaStep` would be the exception". A top-level function is not a type, so D6 never argued about
  this identifier;
- **`petich` was the library's only bare public function.** Every other public function in the
  published surface is an extension or a `fun interface`. So a lowercase `petich` in this package
  meant the package or the instance — `PetichMemberContext.petich` hands the instance to every member
  — and the builder was the single exception to that too;
- **naming it for its return type keeps D6 exactly as it stands**: the types are `Petich*`, and
  "saga" stays in prose.

**`saga<T>(…)` is still available and is a person's decision, not this rename's**, because it puts
the domain word into an identifier and that is what D6 settled. Recorded here so the choice is made
knowingly rather than by default: the switch is a substitution over about 110 call sites and a
version bump in two consumers, which is what this rename cost and is the measurement rather than an
estimate.

**Correction found while implementing B-31: "and `onUnowned`" was too strong, and removing it would
have replaced a silence with something worse.** The decision above bundled two things that turned out
not to travel together. `engineFor` goes: it answered "which engine owns this saga", and the engine
answers that itself now — `PetichEngine.owns` — so there is no mapping left for an application to
leave an entry out of. The callback beside it did not go, because **its cause changed rather than
disappeared**.

What `onUnowned` was documented for — somebody introduced a saga type and forgot to register an
engine — genuinely cannot happen any more: with one engine, a type with no definition cannot be
*started* either, and `process` refuses it by name at the caller (#78). What remains is the case the
old name never described: **a row written when a definition existed and read after it stopped
existing** — a rollback, or a decommissioned saga type whose rows have not drained. The item's own
"does not cover" note named exactly this and asked for an answer that is not a crash.

Deleting the callback would have made the sweeper fall through to `process`, whose answer to an
unknown type is to end the saga `FAILED`. That is right on the forward path and irreversible here: a
deploy that drops a definition would have the sweeper walk every expired saga of that type and end
them all, for a reason the next deploy fixes. So the sweeper **skips** and the callback stays, renamed
`onUnknownType` to say what it now means. A non-zero rate on it means a definition is missing, not
that a saga is broken.

The general shape is worth keeping: *a parameter whose removal is justified by "its cause is gone"
needs the cause enumerated, not assumed.* Two causes shared one name here, and only one of them left.

### D6. The vocabulary is `Petich*`, and the prose still says "saga"

Decision: `PetichStep`, `PetichCheck`, `PetichDefinition`, `PetichStepContext`.

Why: every type in the library is already `Petich*`, and a lone `SagaStep` would be the exception.
The word "saga" is the domain — the README opens with "a distributed saga engine for Kotlin" — and
the product name is what types carry. This is the split the repository already writes by.

### D7. No compatibility adapter for the old model

Decision: the old `PetichInterceptor` is removed in the same release, not deprecated beside the new
types.

Why:

- an adapter preserves exactly the discipline this work exists to remove: an old-style step's
  `Reject` is not obliged to roll back, so the trap of 1.2 survives inside the adapter;
- the consumers are ours, and 27 steps is the corpus this is measured against rather than a
  liability;
- the price: a consumer cannot take the new version one saga at a time. For an outside consumer that
  would be disqualifying; there is none.

---

## 3. Risks and open questions

**Risk 1. Cross-cutting members become invisible again.** A global check mixed in at a phase
boundary is a step in the chain that is not written where the saga is read — which is the defect this
whole model removes, returning by another door. Mitigation: globals are one declared, ordered list;
`describeChain` renders them **inline** in each saga's chain; the fingerprint covers them, so a
deploy that adds one is caught by the same mechanism as a deploy that moves a step. **Closed by
B-30, and the open question with it — see D9.**

**Risk 2. The step key becomes a migration.** Renaming `"reserve-stock"` re-points every saga in
flight, exactly as moving a position does today — the failure mode moves rather than disappearing,
and it moves to something a person types. Mitigation: the fingerprint refuses loudly, as it already
does; the documentation says that a key is a stored identifier and not a label. Open: whether the
builder should refuse a key that is not a valid identifier, to discourage prose.

**Risk 3. The record of D4 is a storage change, and this repository has just paid for one.**
B-24 exists because three columns arrived and were named nowhere. Mitigation: the upgrade notes and
`tools/schema-notes-audit.py` are already in place and will fail if this lands unnamed.

**Open question 1 — settled by shashki, not by konekt, and as a distinct verb (B-37).**
`InterceptorResult.Resuspend` exists for a member re-entered rather than passed, and the definition
model had no way to ask for it. The guess was that a wizard in konekt would decide it; what decided
it was shashki's order saga, where `OfferStep` offers a ride to the nearest driver, waits, and on a
decline offers it to the next — **the member is the cascade**, so the next answer has to come back to
it. Expressed with `suspendFor`, which stores the position one past the member, the second answer
belongs to whatever comes after and the saga assigns a ride nobody accepted.

`ctx.resuspendFor(action, ttl)` beside `ctx.suspendFor`, and **not** the `again = true` flag the
hypothesis proposed. What differs is not a detail of the waiting: it is whether this member runs
again, which is the difference between a hold taken once and a hold taken per answer. A boolean
argument hides that at the call site, where it is the only thing worth seeing.

**Open question 2 — settled while writing konekt's announcing member (B-32), and not the way the
hypothesis guessed.** The guess was that such a member wants a `PetichCheck` or a verb of its own.
It wants neither: **a member that announces is a `PetichStep` whose `compensate` is empty**, and the
builder's own ordering rule is what proves it. A check may not follow a step, so a check in
`POST_PROCESSING` — after every effect — is refused, and refused correctly: a check exists to be able
to refuse, and refusing there would keep what ran.

The empty `compensate` it is left with is **not** the empty compensate this stage removed. Those were
validations, written empty to satisfy a type that demanded an undo from something that never did
anything. This one is a true statement about a member that did act: an announcement committed to the
outbox is delivered at least once and cannot be un-announced. `= Unit` says so, and there is nothing
better to write.

**Correction found while implementing B-32 — the model could not announce anything.** The first real
saga taken from a consumer ends with a member whose entire job is to emit an outbox event in the same
write as the state change, and `PetichMemberContext` had no way to say it. Two more of the same kind
were behind it: a **compensation** that announces what it undid (konekt overrides
`compensateWithEvents` for exactly that), and a `PetichSideEffect` attached to a member's write. All
three were expressible in the interceptor model and none in the definition model, because the
outcomes were designed around what a member *decides* and not around what it wants *committed
alongside*. `ctx.emit` and `ctx.attach` close it. **This is what an acceptance item is for**: it took
one saga of three members to find, and no amount of reading the design would have.

One asymmetry inherited rather than introduced: `InterceptorResult.Suspend` carries side effects and
**not** outbox events, so a member that announces and then suspends loses the announcement.

**Correction, and the sentence above was wrong about the part that matters (B-35).** "Not made worse
by it" is exactly backwards. An interceptor had no channel to announce through on a suspension, so
nobody could write the mistake; `ctx.emit` before `ctx.suspendFor` compiles, runs, and disappears.
This stage turned a missing capability into a silent loss, which is the objection it raised against
the empty `compensate` in the first place, made by the thing that was meant to retire it.

### D8. What each outcome carries, and why a refusal carries nothing

Settled while closing B-35, because "whatever the engine happens to do" is not a design.

**`Proceed` and `Suspend` carry everything the member asked for.** Both commit forward progress —
a suspension writes the row to `PENDING_SIGNATURE`, which is as real a write as any — so an
announcement has something to ride on. konekt's authorisation is the case: it holds a subscriber's
money and waits, in one member, and "we are holding your funds, confirm within five minutes" belongs
in that write. `Suspend` gains an `outboxEvents` field beside the `sideEffects` it already had. That
`sideEffects` exists at all is the tell: somebody hit this gap once, gave the durable timer a field,
and left the announcement without one.

**`Reject` and `Compensate` carry nothing, and that is a decision rather than the status quo
preserved.** Both begin a rollback. petich will not announce work it is in the middle of undoing —
an event committed with the write that starts a rollback describes something that is about to stop
being true, and the outbox is at-least-once, so it cannot be recalled. **A rollback's own word
belongs to the compensations**, which may announce freely: `undo` returns what its context emitted,
and konekt's `HoldFunds` announces the reversal from exactly there.

**The hole that leaves, named rather than waved past.** The *refusing* member's own compensation does
not run — a reported refusal means the member did nothing, so the rollback starts before it — and so
anything that member emitted has no owner at all. Two answers, both taken:

- **A check cannot announce.** `emit` and `attach` moved from `PetichMemberContext` to
  `PetichStepContext`. A check's entire contribution is whether the saga continues; it has no
  compensation to inherit its word, and a check that refuses leaves nothing behind that could carry
  one. The model stops accepting what it cannot honour — which is the whole argument of D2 applied
  to a second question. konekt's purchase validation writes its refusal through its own ledger port
  and is unaffected; that it had to is the evidence the hole was real.
- **A step that announces and then refuses is counted, not dropped.**
  `PetichEngineMetrics.onAnnouncementDiscarded(type, stepKey, count)` names the member. Deliberately
  not `onDroppedEvents`, whose own documentation calls that one a mistake "reached by accident rather
  than by decision" — a repository with no outbox at all. This one is the decision, and a counter
  meaning both would answer neither question. A non-zero line is a member written as though
  announcing and refusing could be done in one breath.

**What made this findable only now:** the whole suite was green while three outcomes out of four lost
announcements. `AnnouncementPerOutcomeTest` states the rule per outcome, and each of its four cases
was checked by breaking the folding that serves it.

**Correction found while implementing B-32 — the defect D5 exists to remove was already written
down, by the consumer, in two places.** An engine handed a saga whose type matches no member ran zero
members and reported `COMPLETED`; that was fixed in #78 and reported here as *found by migrating a
consumer*. It was not. konekt's composition root states it exactly — petich "resolves nothing by type
itself — an engine is a fixed interceptor list — so handing a top-up to the purchase engine finds no
step that supports its payload, completes a saga that did nothing, and reports success" — and its
feature module repeats it for the Koin qualifier that works around it. The consumer diagnosed the
defect, paid for a workaround of two engines over one saga table, and the library never heard. What
the migration contributed was removing the workaround so the defect could surface. **The registry of
D5 is not an ergonomic improvement; it is the removal of a workaround a consumer has been carrying.**

### D9. A global is a check, and it goes through the one seam everything else goes through

Settled while closing B-30, which is where Risk 1 was to be answered.

**A global may not be a `PetichStep`.** A member that acts has to be undone, and a global's undo
would run inside *every* saga's rollback, at a position no saga's author wrote. The author reads
their definition, counts its members, and reasons about a rollback that walks more of them than
they can see — which is the defect this whole stage removes, arriving from the other side. A check
has nothing petich must undo (D2), so a global lengthens the forward pass and no rollback at all.

**That does not make a global pure, and saying so would be the dishonest version of this rule.** A
global may act through its own ports; plenty of audit does. What the type forbids is acting in a way
that leaves petich owing somebody an undo. It also cannot announce, since `emit` belongs to a step
for the same reason (D8/B-35) — so a cross-cutting concern that needs a transactional write of its
own is a concern that wants to be a member of the sagas that care, not of all of them.

**The mechanism is one line in `chainFor`, deliberately.** Every question anybody asks about a chain
— what runs, in what order, what the fingerprint covers, what a mismatch prints — was already
answered by that single function. Putting globals in front of their phase's declared members there
makes them inline everywhere *by construction*, rather than by four call sites each remembering to
include them. The alternative shape, a table of globals rendered beside each chain, is the one that
rots: it is a hand-written list beside a growing set.

**Rejected: attaching globals per saga in the definition.** Explicit, and a copy of the same three
lines in every definition — which is what `supports` was for, badly.

**Correction found while implementing.** `describeChain` took a *payload* and resolved the chain by
it, while a definition is resolved by *type*. So for every saga on the definition model it described
the interceptor chain — the empty one — including inside the message a chain mismatch prints, which
exists to tell a reader what the chain is. The diagnostic built for this stage printed five dashes
to exactly the people the stage is for. It now takes the type; the parameter is defaulted, so the
interceptor model keeps what it had.

**One namespace for keys, refused at construction.** A key identifies a member in the saga's row —
`stepRecords` is keyed by it, the fingerprint is built from it — and a global shares its chain with
every definition there is. Two members under one key would overwrite each other's record and make
the fingerprint ambiguous, so the engine refuses the collision by name.

### D10. A member reads its key and nothing else about where it sits

Settled while closing B-36, which shashki's migration produced.

**The key is exposed; the phase is not.** `PetichMemberContext.stepKey` gives a member the string its
definition declares it under, the same value on the forward pass and inside its own compensation. The
engine had it all along — it builds the context with the key, and uses it to find that member's
record and to build the fingerprint — so this was a private field, not a new fact.

**Why the phase stays out, when it was the thing shashki actually used.** Its settlement steps name a
span `saga.settlement.$phase.${this::class.simpleName}`, so the obvious repair is to hand back the
phase. But the phase was never the member's address; it was its *bucket*, and the class name was
doing the identifying. The key is the address, it is unique within the definition by construction
(the builder refuses a duplicate), and `saga.settlement.${ctx.stepKey}` is both shorter and more
precise than what it replaces. Re-exposing `phase` would put half the definition back inside the
member, which is D1 undone for the convenience of one string.

**And `petich.currentPhase` is not the same fact, which is the trap.** It is the *saga's* position. On
the forward pass it happens to equal the member's declared phase; during a rollback it is where the
rollback has reached. A compensation naming a span from it would name the wrong thing, in the one
direction where the name matters most — and would look right in every test that only walks forward.

**What this leaves a consumer:** a member can say *which* member it is. It cannot say what phase it
was declared in, and the answer to "why do you need to?" has so far been observability, which the key
serves better.

**Open question 3. Is the phase list still five?** The phases came from a banking pipeline
(`ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING`). With order given by the
definition, a phase is only an insertion point for globals and a timeout table. Hypothesis: they
stay, because both consumers' timeouts are per phase and the globals need somewhere to attach.

### D11. A definition runs in the order it is read, and the builder refuses one that does not

Decision: a member whose phase is lower than the member declared above it is refused where it is
declared, with both keys and both phases in the message. Non-decreasing, not increasing — several
members of one phase are what declaration order is for.

Why:

- **the model's whole claim is that the order is written where the saga is read.** Without this the
  order is written and *not* read: members run in phase order, and a definition could read top to
  bottom while running bottom to top;
- **it has already cost a debugging pass.** `SuspendedTtlTest` was written that way while migrating
  the suite (B-33) — an AUTHORIZATION member declared below an EXECUTION one, suspending before the
  step it was meant to follow had run, so there was nothing to roll back and the case asserted the
  opposite of what it meant. The file said one order and the engine did another;
- **rejected: a type-state builder.** One comparison against the previous member is the whole rule.
  A phantom type per phase would spread through every signature to refuse the same thing.

**Correction found while implementing — it replaced a rule rather than joining one.** The builder
already refused a *check* after a step, on B-20's reason: a check has no `compensate`, so a refusal
from one after an effect keeps what that effect did. That rule was described in D3 as the thing that
keeps a check from sitting after an effect, and as a separate mistake from this one. It is not
separate any more. B-39 took the step overload off `authorize`, so every check now sits in a phase
below every step — "a check after a step" and "a phase that goes backwards" became the same
sentence, and the older rule was this one's special case.

So it was absorbed, not left beside it: **two guards over one mistake hide which of them is
load-bearing**, and a suite can stay green while the one everyone cites does nothing. Its reason
survives in the message, because it is the concrete cost and not a restatement of the rule. The
proof that the absorption is real rather than a rename is a mutation: making the new comparison
vacuous fails the two old check-after-step tests along with the three new ones.

### D12. An announcement is a third member type, and its exception is counted rather than rolled back

Decision: `announce` takes a `PetichAnnouncement<P>` — one method, no `compensate` — whose context
carries `emit`, `attach` and `enrich` and none of `reject`, `fail`, `suspendFor` or `resuspendFor`.
An exception it throws is counted through `onAnnouncementFailed` and the saga completes; what it
asked to have committed before throwing rides with that completion.

Why:

- **by the time it runs, the work is done.** The stock is reserved and the money is captured;
  rolling all of it back because a notification did not go is the wrong answer, and `PetichStep`
  offered it as the natural one;
- **the portfolio already followed this rule by discipline.** shashki's settlement sends a receipt
  by mail before emitting and swallows the send's failure by hand, because `SendReceiptUseCase` says
  a settlement rolled back over a mail server would be the tail wagging the dog. Same gap as B-39: a
  rule kept by an author's memory rather than by a type;
- **rejected: the reviewer's shape, a type that returns the event and takes no context.** It answers
  "may an announcement do I/O" as well as "may it fail the saga", and only the second question
  matters. shashki is the counter-example to the first and is not a mistake.

**Correction found while implementing — the type was the smaller half.** Withholding `fail` stops a
member from *deciding* to end the saga and does nothing about one that throws, and a throw meant
exactly the same rollback. So the promise would have been a comment: `PetichAnnouncement` without
the engine change is a type that documents an intention the runtime ignores. The engine change is
what the tests hold, and it is what the mutation kills.

**What it announced before it threw is kept, and that is a departure from B-35.** A refusal carries
nothing because it begins a rollback and petich will not announce work it is undoing. There is no
rollback here, so there is nothing to protect by dropping the event — and dropping it would lose the
one write the outbox exists to make certain.

**May it suspend? No, and the answer is the context's shape rather than a rule.** Nothing in the
portfolio parks a saga from POST_PROCESSING, and "no" is cheaper to relax later than to impose.

**A consumer's suite was asserting the old answer, which is how the cost was measured.** shashki's
`dying after any phase leaves no held payment and no reserved driver` ran the same death through
four members, `publish-assigned` among them, and asserted that a death there released the fare and
freed the driver — a ride un-assigned because the sentence announcing it could not be built. That
member came out of the list and got a case of its own with the opposite assertion. The settlement
suite had the identical pair.

### D13. A member names its remote effect before the call, because a record cannot say it happened

Decision: `PetichMemberContext.idempotencyKey` — a deterministic `"<saga id>:<member key>"`, the same
string on the forward pass, on a re-run after a version conflict, and inside the compensation. It is a
rule in the README's **What it asks of a member**, not advice.

Why:

- **the guard the page used to recommend is blind in the case the rule above it describes.** "Undo
  what the record says happened, return quietly when there is none" reads *it never landed* out of no
  evidence at all. The step calls the far side, the far side commits, the answer is lost, the timeout
  fires — and the member never reached `record`, because the identifier it would have written comes
  back *in* the answer that was lost;
- **no write ordering fixes it**, which is the part worth keeping: `step_records` are folded in from a
  `finally` and on both `catch` arms, so a member that records and then dies does carry its record
  into its own undo (B-29). The hole is not when the record is written. It is that at the moment of
  the timeout there is nothing to write;
- **rejected: write the intent before the effect.** It works, and it costs a write per acting member,
  which breaks the eight-write budget the README publishes. Naming the effect costs nothing: both
  halves of the string are already on the row;
- **rejected: leave it to the member.** A member can build the string, and two members will build it
  two ways — and the two sides have to spell it identically or the rollback cancels nothing. Same
  argument as B-36 for `stepKey`.

**Corroboration rather than invention, found by looking at the consumers.** konekt's `HoldFunds`
already passes `ctx.petich.id` into `balances.hold(...)`: the shape was arrived at by hand, one
consumer at a time, keyed by the saga instead of the member because that saga holds money in one
place. shashki did not, and its hold is open to exactly this — filed there as B-91 rather than fixed
here, because its `PaymentGateway` port cannot express a caller-chosen name yet.

**What `record` is still for is unchanged**: the reservation id, the hold id — what a rollback
*needs*. What it cannot be is evidence that the effect happened, because that is a fact only the far
side has. D4 stands; this is the other half of it.

### D14. A refused chain is counted, not marked — because the condition is recoverable

Decision: `chainMismatch` fires `PetichEngineMetrics.onChainRefused(type, phase)` on every pass and
**still writes nothing to the row**. No new status, and the README carries the runbook instead.

Why:

- **the refusal was the only failure in the engine with no signal of any kind.** Its neighbour — a
  chain that could not be assembled at all — has had `onChainUnavailable` since it existed, so the
  more specific and more damaging case was the invisible one. The row keeps its status, its position
  and its deadline, which is what a healthy saga's row reads;
- **fires on every pass, deliberately.** The mismatch is not an event that happened once: it holds
  while two deployed versions disagree. A counter that went quiet after the first sweep would read as
  "resolved" to anybody watching, which is worse than no counter. The rate is the measurement;
- **no saga id on the counter.** An unbounded dimension does not belong in a metric; the refusal's
  message names the saga and both fingerprints, and that is where identity belongs.

**Rejected: a terminal status, which is what the review proposed.** It is the natural answer by
analogy with `COMPENSATION_FAILED`, and the analogy is what breaks. An exhausted rollback is damage:
the saga is half undone and nothing will touch it again. A refused chain is a **disagreement between
two deployed versions** — roll the deploy back and every refused saga resumes where it stopped and
finishes. Marking those sagas dead would convert a recoverable situation into an unrecoverable one,
because a terminal status cannot be un-set by the deploy that fixes the cause. The runbook's second
step is a test rather than a sentence for this reason.

**Left open: a non-terminal status a person could query** — `SELECT ... WHERE status = 'REFUSED'`
answers "which sagas are stuck" in a way a counter cannot, and self-heals on the next matching pass.
Its price is a write on the one path that currently writes nothing, a version bump that can race a
healthy process, and a new enum value every consumer's exhaustive `when` has to handle. That is a
person's call, not this item's, and it is recorded here so it is made knowingly rather than by
default.

**No consumer had to change**, which is the counter's other argument: the method is defaulted, and
the only implementation in the portfolio — shashki's `RefusingMetrics` — overrides one unrelated
method.

---

## 4. What happens next

The order of work and the acceptance criteria live in the backlog, as `stage-9-definition`. The
first substantive step is the types and the builder, because D2 and D3 decide what everything else
can express; the record of D4 is second because it is what makes the migration of a real consumer
honest rather than mechanical. **0.3.0 goes to Central before any of this starts** — it carries five
fixes that should not sit unpublished behind a redesign, and the published version should not be the
one with the defects those fixes removed.
