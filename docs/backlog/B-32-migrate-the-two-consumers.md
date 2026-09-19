---
id: B-32
title: "Rewrite konekt's and shashki's sagas in the new model — the acceptance"
status: wip
priority: P1
size: L
stage: stage-9-definition
blocked_by: [B-29]
---

# B-32 — 27 steps are not a liability, they are the corpus

Both services exist to exercise this portfolio's technology. Migrating them is not a cost to be
minimised but the test of whether the model is better, and the one that finds what a library's own
suite cannot — which this repository has written down twice and watched happen twice this week.

- **Each of the three defects the old model produced has a named site to check.** konekt's
  `TopUpInterceptors.kt:82` and shashki's `CaptureStep` become `ctx.recorded() ?: return` instead of
  a ledger lookup and a wrong fallback; every empty `compensate` becomes a `PetichCheck` with nothing
  to write; `PurchaseDomain.kt`'s status mapping stops being a `when` over an engine enum.
- **What to watch for is the step that does not fit.** konekt's `HoldFundsInterceptor` holds money and
  suspends in one step; shashki's `CaptureStep` branches on `payload.kind` inside itself. Those are
  the two places where the new types either earn their keep or are found wanting, and they are the
  reason this item is acceptance rather than bookkeeping.
- **Rejected: migrating one and calling it done.** The two differ in shape — konekt suspends for a
  human and holds money, shashki captures and refunds against a gateway — and one of them alone would
  confirm whatever the model already assumes.
- **Does not cover:** removing the old model, which is
  [B-33](B-33-remove-the-interceptor-model.md) and comes after this, not with it.

- AC: both services run their suites green on the new model; every empty `compensate` is gone; the
  two defects filed upstream are closed by the model rather than by a guard; anything the model could
  not express is written into `docs/research/research-petich-dsl.md` as a correction found while
  implementing.
- Anchors: `docs/research/research-petich-dsl.md`
