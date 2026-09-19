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
- the price: a step that both acts and refuses must be a `PetichStep` that fails — which is what it
  already is.

### D3. The verb places the step; the type names its role. They are different axes

Decision: `enrich`, `validate`, `authorize` and `step` place a member in a phase. `authorize` accepts
**both** types.

Why:

- konekt's `HoldFundsInterceptor` holds money and then suspends, as one step, on a recorded decision
  of its own (D5 there). Under a model where the verb implied the type it would have to be cut in
  two to satisfy the model, and its argument for being one is sound;
- the builder forbids `validate` after `step`, and that ordering rule — not the verb — is what keeps
  a check from sitting after an effect. **Without it the trap of 1.2 returns through the back door.**

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

### D5. A definition is a value, and the engine keeps a registry of them by type

Decision: `petich<P>("order") { … }` returns a `PetichDefinition<P>`; the engine holds definitions
keyed by `type`.

Why:

- 1.5: `Petich.type` already carries the identity and the definition was the missing half;
- it removes `engineFor` and `onUnowned` from the sweeper: "which engine owns this saga" stops being
  a lambda an application must keep in step with its own types, and a forgotten registration stops
  being a class of silent expiry;
- naming matters and is not free: `Petich` is the **instance** — a row with an id, a status and a
  version. `PetichDefinition` is what a builder returns. A function `petich(…)` beside a class
  `Petich` is legal Kotlin and would read badly if the result were not named for what it is.

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
deploy that adds one is caught by the same mechanism as a deploy that moves a step. Open: whether a
global may be a `PetichStep` at all, or only a `PetichCheck`.

**Risk 2. The step key becomes a migration.** Renaming `"reserve-stock"` re-points every saga in
flight, exactly as moving a position does today — the failure mode moves rather than disappearing,
and it moves to something a person types. Mitigation: the fingerprint refuses loudly, as it already
does; the documentation says that a key is a stored identifier and not a label. Open: whether the
builder should refuse a key that is not a valid identifier, to discourage prose.

**Risk 3. The record of D4 is a storage change, and this repository has just paid for one.**
B-24 exists because three columns arrived and were named nowhere. Mitigation: the upgrade notes and
`tools/schema-notes-audit.py` are already in place and will fail if this lands unnamed.

**Open question 1. Does a suspension's re-ask survive?** `InterceptorResult.Resuspend` exists for a
wizard re-asking a question — the step is re-entered rather than passed. Hypothesis: it becomes
`ctx.suspend(again = true)` or a distinct return, and the wizard in konekt is where it is settled.

**Open question 2. What does `step(key) { }` without a compensation mean?** A lambda member is
convenient for a notification, and it asserts "there is nothing to undo" silently. Hypothesis: it is
legitimate only after the last effect, and the honest form names it — a separate verb rather than an
overload. Settled by writing konekt's `AnnounceTopUpInterceptor` in the new model.

**Open question 3. Is the phase list still five?** The phases came from a banking pipeline
(`ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING`). With order given by the
definition, a phase is only an insertion point for globals and a timeout table. Hypothesis: they
stay, because both consumers' timeouts are per phase and the globals need somewhere to attach.

---

## 4. What happens next

The order of work and the acceptance criteria live in the backlog, as `stage-9-definition`. The
first substantive step is the types and the builder, because D2 and D3 decide what everything else
can express; the record of D4 is second because it is what makes the migration of a real consumer
honest rather than mechanical. **0.3.0 goes to Central before any of this starts** — it carries five
fixes that should not sit unpublished behind a redesign, and the published version should not be the
one with the defects those fixes removed.
