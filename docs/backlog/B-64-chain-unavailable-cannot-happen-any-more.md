---
id: B-64
title: "onChainUnavailable counts a failure the definition model cannot produce"
status: open
priority: P3
size: XS
stage: stage-12-tracer
blocked_by: []
---

# B-64 — a counter and a branch that outlived their cause

`prefixFingerprint` catches an exception from `chainFor`, counts it through
`PetichEngineMetrics.onChainUnavailable`, and writes the saga without a fingerprint. Its comment
names the cause: "a supports() that throws, or a tie refused by configuration". Both belonged to the
interceptor model, which B-33 removed. `chainFor` now filters two lists and builds runs from them;
nothing in it throws, and no test sends the counter — found while
[B-58](B-58-a-per-saga-event-hook.md) looked for a way to send its matching event and found none.

- **The decision:** either name a cause that still exists and test it, or remove the branch, the
  counter and the metric method — a breaking change to `PetichEngineMetrics`, but the method has a
  default body, so only an implementation that overrides it stops compiling.
- Not covered: the fingerprint itself.

## Acceptance

- Either a test that makes `onChainUnavailable` fire through the public API, or the method, its
  `GuardedMetrics` forwarding and the `catch` in `prefixFingerprint` are gone, and the README's
  Observability section says which.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonMain/kotlin/PetichEngineMetrics.kt`,
  `petich-core/src/commonMain/kotlin/Guarded.kt`
