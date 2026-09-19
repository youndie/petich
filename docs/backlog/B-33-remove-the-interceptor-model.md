---
id: B-33
title: "Remove PetichInterceptor, and do not leave an adapter behind"
status: open
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
  documents; the engine holds no unchecked cast; the README describes one model.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`, `README.md`
