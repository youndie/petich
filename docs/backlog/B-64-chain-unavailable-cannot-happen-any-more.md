---
id: B-64
title: "onChainUnavailable counts a failure the definition model cannot produce"
status: done
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

## Findings

**No cause left, so removed.** `chainFor` filters the globals and one definition's members by phase
and wraps each in a run object; members are instances built when the definition was declared, not in
`chainFor`. The metric's own KDoc had already noticed ("much harder to reach than it was") and kept
it "for what is left", naming "a member whose construction threw" — which is not a thing `chainFor`
does. Removed: `PetichEngineMetrics.onChainUnavailable`, its `GuardedMetrics` forwarding, and the
`catch` in `prefixFingerprint`, which now returns a `String`; `chainMismatch` loses the `?: return
null` that stood for "could not be assembled".

**Breaks nothing published.** The method arrived with B-21, after 0.2.0: the `PetichEngineMetrics`
class in `petich-core-jvm-0.2.0.jar` on Central has `onCompensation`, `onDroppedEvents`,
`onDroppedSideEffects`, `onOptimisticRetry`, `onProcessAttempt`, `onStateUpdateRetry`, `onSuspend`
and nothing else. No consumer overrides it (`git grep onChainUnavailable` in konekt, shashki and
proba is empty), and the README's Observability section never listed it, so it needs no line there.

**No test added, on purpose.** The claim is that nothing can reach the path, and a removal is the
only form that claim takes in code: there is no branch left for a test to exercise. The existing
suite is the check that nothing depended on it — `./gradlew build` on the Linux box green, conformance
included, 216 tests in `petich-core` on the JVM.
