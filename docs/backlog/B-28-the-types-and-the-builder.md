---
id: B-28
title: "PetichStep, PetichCheck and a definition that says the order out loud"
status: wip
priority: P1
size: L
stage: stage-9-definition
---

# B-28 — the flow of one saga, written where it can be read

An interceptor declares `phase`, `priority` and `supports(payload)`; the engine filters, sorts and
walks. The flow of a single saga is written nowhere — to read it, a person collects every interceptor
whose `supports` accepts the payload and sorts them in their head. This item replaces that with a
definition.

```kotlin
val orderPetich = petich<OrderPayload>("order") {
    enrich(LoadCustomer(customers))
    validate(CheckLimits(limits))
    authorize(RequireConfirmation(ttl = 5.minutes))

    step("reserve-stock", ReserveStock(stock))
    step("charge", ChargeCard(psp))
}
```

- **Two types, and the second cannot roll anything back** (research D2). `PetichCheck<P>` returns
  proceed / reject / suspend and has **no** `compensate`; `PetichStep<P>` returns proceed / suspend /
  fail, and fail always rolls back. 12 of the 27 compensations in the two consumers are empty, and
  refusing-without-rollback-after-an-effect stops being representable rather than guarded.
- **The verb places, the type names the role** (D3). `authorize` accepts both, because konekt holds
  money and suspends in one step on a recorded decision of its own. **The builder refuses `validate`
  after `step`**, and that ordering rule — not the verb — is what keeps a check from sitting after an
  effect. Without it the trap returns through the back door.
- **The key is the identity.** `"reserve-stock"` is what goes in the saga's row, so the fingerprint
  from [B-21](B-21-the-chain-is-addressed-by-position.md) compares names rather than positions and
  `describeChain` is the definition read back.
- **Rejected: keeping `supports`.** It is the only reason the engine casts `payload as T` unchecked
  and the only reason `withPayloadDiagnostics` exists to rename the resulting `ClassCastException`.
  Naming the payload once on the definition deletes both. The price is that a step cannot serve two
  payload types; nothing in either consumer does.
- **[B-27](B-27-release-0-3-0-before-the-redesign.md) was dropped**, so nothing waits on a Central
  release: publishing 0.3.0 would have pinned the interceptor model there one release before
  [B-33](B-33-remove-the-interceptor-model.md) deletes it.
- **Does not cover:** the per-step record ([B-29](B-29-what-a-step-did-belongs-to-that-step.md)),
  globals ([B-30](B-30-globals-inline-in-the-chain.md)), the registry
  ([B-31](B-31-the-engine-knows-which-definition-owns-a-saga.md)) or removing the old model
  ([B-33](B-33-remove-the-interceptor-model.md)). This one is the vocabulary everything else speaks.

- AC: a saga's order is readable in one place and asserted by a test that snapshots `describeChain`;
  a `PetichCheck` cannot be placed after a `PetichStep`, proved by a builder test that fails;
  a `PetichCheck` has no `compensate` to write; the engine contains no unchecked payload cast.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `docs/research/research-petich-dsl.md`
