---
id: B-33
title: "Remove PetichInterceptor, and do not leave an adapter behind"
status: wip
priority: P1
size: M
stage: stage-9-definition
blocked_by: [B-32]
---

# B-33 — two models in one engine is the thing this stage was avoiding

Once both consumers run on definitions, the old surface is dead weight: `PetichInterceptor` with its
`phase`, `priority` and `supports`, `InterceptorResult` with `Reject` and `Compensate` side by side,
`tryIntercept`, `tryCompensate`, `withPayloadDiagnostics`, and the unchecked cast they exist around.

- **The decision (research D7): no deprecated adapter.** An adapter preserves exactly the discipline
  this work removes — an old-style step's `Reject` is not obliged to roll back — so the trap survives
  inside the thing meant to retire it. Two models in one engine also means every guard has to hold
  for both.
- **The price, stated: a consumer cannot migrate one saga at a time.** For an outside consumer that
  would be disqualifying. There is none; both are ours and both are already through
  [B-32](B-32-migrate-the-two-consumers.md) by the time this runs.
- **Rejected: leaving the types deprecated for one release.** Deprecation without an adapter buys a
  compiler warning and nothing else, and the release notes say more than a warning does.
- **Does not cover:** the version number and what the notes say. A removal of the public surface is
  not a minor bump, and the upgrade section from
  [B-24](B-24-a-release-that-adds-a-column-names-it-nowhere.md) is where a consumer will look.

- AC: no reference to `PetichInterceptor` or `InterceptorResult` remains in the sources or the
  documents; the README describes one model; **the engine holds exactly one unchecked payload cast**
  — moved here from [B-28](B-28-the-types-and-the-builder.md), which could not meet it: the
  interceptor arm *is* a cast per member, and even without it one remains where a polymorphic stored
  payload meets a generic definition. One declared place is the honest target, and "none" was not.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`, `README.md`

## Iteration 1 — 2026-09-20

**Every production source in the repository compiles with the interceptor model gone.** Removed:
`PetichInterceptor` and its `phase`, `priority`, `supports`, `tryIntercept`, `tryCompensate`,
`withPayloadDiagnostics`; the engine's `interceptors` parameter; `InterceptorRun`;
`interceptorChainFor` and the fallback arm of `chainFor`. `InterceptorResult` is gone as a name — it
was the engine's own vocabulary by the end, not a consumer's, so it is now `internal MemberOutcome`.

**`chainFor` has no fallback any more, and that is the one behavioural change.** A saga whose type
has no definition used to walk the interceptor list; there is no list. It walks nothing, and
`doProcess` refuses it by name rather than completing it — which is exactly what #78 was cut for, now
carrying a case it was not written for.

**`tools/native-consumer-probe` moved with them.** It is a real consumer of the published surface,
not a test, and leaving it on the old model would have left the artifact nobody runs the one thing
still proving the old model works.

**What is left is tests, and only tests: 502 compile errors across 28 files.** They are not
mechanical in the way the count suggests — each encodes a guarantee, and translating one badly is how
a green suite comes to mean less than it did:

`AccessScoringPetichEngineTest`, `BadgeIssuancePetichEngineTest`, `StockMovePetichEngineTest` (the
three corpus fixtures, ~34 references each), `ChainFingerprintTest`, `CompensationFailureTest`,
`CompensationGivesUpTest`, `ConcurrentProcessTest`, `EngineConfigTest`, `EngineDefectsTest`,
`FailedStepCompensationTest`, `InterceptorPriorityTest`, `OutboxEventTest`, `PetichTest`,
`RejectRollsBackTest`, `ResumeInterceptorTest`, `ResuspendTest`, `SideEffectTest`, `StuckSweepTest`,
`SuspendedTtlTest`, `SweepClaimTest`, `TerminalReplayTest`, `TimeoutTest`, `VersionConflictTest`,
`WriteCountTest`, plus `OneTransactionTest` and `SagaTimerSinkTest` in chronik and
`PetichRoutingTest` in ktor.

**Two of those names are the work rather than a rename.** `InterceptorPriorityTest` tests a concept
that no longer exists — priority — and `ResumeInterceptorTest` and `ResuspendTest` cover ground
`ReaskAtTheSameMemberTest` now covers from the other side. Each needs a decision about whether what it
asserts still has a subject, not a search and replace. That is the judgement this iteration stopped
short of rather than rushed.

**Not yet done from the AC:** the README still describes both models, and the unchecked-cast count
has not been verified as exactly one.
