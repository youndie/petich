---
id: B-31
title: "engineFor and onUnowned exist because no value says what an order saga is"
status: open
priority: P2
size: S
stage: stage-9-definition
blocked_by: [B-28]
---

# B-31 — a mapping the application maintains for a question the library can answer

`SuspendedPetichSweeper` takes `engineFor: (Petich) -> PetichEngine?` because an application keeps
several engines over one store, each with its own interceptor list; a saga whose type is unregistered
is skipped and reported through `onUnowned`. That callback's own documentation says what it is for:
somebody introduced a saga type and forgot to register it, and those sagas pile up expired for ever.

`Petich.type` has carried the identity all along — `"order"`, `"move"`, `"settlement"`. What was
missing is a value on the other side of it.

- **The decision (research D5):** the engine holds `PetichDefinition`s keyed by `type`, so it answers
  "which definition owns this saga" itself. `engineFor` and `onUnowned` go.
- **It removes a class of silent failure rather than a parameter.** A forgotten registration becomes
  a definition that does not exist, which fails where definitions are registered, not months later in
  a sweep nobody is watching.
- **Rejected: keeping the callback as an override.** Two ways to answer one question, and the one
  that is wrong is the one that stays silent.
- **Does not cover:** what happens to a saga whose type has no definition **at all** — a row written
  by a version that had one. That is a real state and it needs an answer that is not a crash.

- AC: the sweeper takes no `engineFor`; a saga of an unregistered type fails loudly at a named place;
  konekt's wiring loses the lambda.
- Anchors: `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
  `petich-core/src/commonMain/kotlin/Petich.kt`
