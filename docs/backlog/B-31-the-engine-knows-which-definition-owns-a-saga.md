---
id: B-31
title: "engineFor and onUnowned exist because no value says what an order saga is"
status: done
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

## Findings — 2026-09-20

**`engineFor` is gone from both places that had it** — `SuspendedPetichSweeper` and chronik's
`SagaTimerSink` — and `PetichEngine.owns` answers what the lambda was asked. An engine with no
definitions owns whatever it is handed, which is exactly what the interceptor model did before
(the application's lambda was the only thing that ever decided), and B-33 removes that branch with
the model.

**`onUnowned` did not go, and the item was wrong to say it would.** Its two causes turned out not to
travel together:

- *"somebody introduced a saga type and forgot to register an engine"* — genuinely impossible now.
  With one engine, that type cannot be **started** either: `process` refuses it by name, at the
  caller, the first time anyone tries (#78). That is the AC's "fails loudly at a named place", and
  it happens where sagas are created rather than in a sweep nobody is watching.
- *a row written when a definition existed and read after it stopped existing* — a rollback, or a
  decommissioned type whose rows have not drained. This is the item's own "does not cover" note, and
  it needed an answer.

**Deleting the callback would have replaced a silence with something worse.** The sweeper would have
fallen through to `process`, whose answer to an unknown type is `FAILED` — right on the forward path,
irreversible here. A deploy that drops a definition would have the sweeper walk every expired saga of
that type and end them all, for a reason the next deploy fixes. So it **skips**, and the callback
stays under the name of what it now means: `onUnknownType`.

The general lesson is in the research beside D5: *a parameter whose removal is justified by "its cause
is gone" needs the cause enumerated, not assumed.*

**Two mutations, both firing what they should:**

| what was broken | what failed |
| --- | --- |
| `owns` claims every saga | the ownership case, plus the three skip tests in the sweeper and the sink |
| an interceptor engine owns nothing | the interceptor case, plus six tests that run sagas through the old model |

The second is worth reading: it shows the `definitions.isEmpty()` branch is load-bearing rather than
defensive, and names exactly what B-33 will have to carry when it removes it.

**Not done here, and it belongs to B-32:** konekt's wiring still passes the lambda. konekt is edited
in its own iteration by standing decision, and B-32 is that iteration — noted in its findings.

- AC — the sweeper takes no `engineFor`: done, and the timer sink too, which the item did not name.
- AC — a saga of an unregistered type fails loudly at a named place: on the forward path, by #78.
  In a sweep it is skipped and reported, deliberately, for the reason above.
- AC — konekt's wiring loses the lambda: deferred to B-32 with the reason.
