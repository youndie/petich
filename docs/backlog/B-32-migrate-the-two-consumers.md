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

## Iteration 1 — 2026-09-20

**The model could not announce anything, and one saga of three members found it.** konekt's top-up
ends with a member whose entire job is to emit an outbox event in the same write as the state change,
and `PetichMemberContext` had no way to say it. Two more of the same kind were behind it: a
compensation that announces what it undid — konekt overrides `compensateWithEvents` for exactly that —
and a `PetichSideEffect` attached to a member's write. All three were expressible in the interceptor
model and none in the definition model, because the outcomes were designed around what a member
*decides* rather than around what it wants *committed alongside*.

`ctx.emit` and `ctx.attach` close it, with a case for each on both targets, and the research carries
the correction. **This is what an acceptance item is for**: no amount of re-reading the design would
have produced it.

**An asymmetry inherited rather than introduced**, found while wiring the above:
`InterceptorResult.Suspend` carries side effects and **not** outbox events, so a member that
announces and then suspends loses the announcement. True before this stage, not made worse by it,
and worth an item of its own — not opened here, because this iteration did not need it.

**The version head moved to 0.4.0** (#76) and `0.4.0.69` is published. B-29's upgrade table already
named 0.4.0 for `step_records` while the head said 0.3.0, so the notes promised a version nobody
could resolve. The consumers now have a coordinate to take.

**konekt landed its own fix for youndie/konekt#48 first** — `cd224a7`, a guard reading its ledger
before undoing a movement. That changes this item's job there: not to close the defect, which is
closed, but to express the same protection in the model's own vocabulary — `ctx.recorded() ?: return`
instead of a lookup the application has to remember to write.

**shashki was not touched: its tree carries another session's uncommitted work** on
`build/take-sborka-0.4.0.84`. Rewriting its sagas under that is how two sessions lose an afternoon,
so it waits for a clean tree.

**Next:** konekt's three sagas — top-up first, since its step is the one with a record to make.

