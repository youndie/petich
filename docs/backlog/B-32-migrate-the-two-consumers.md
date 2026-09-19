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


## Iteration 2 — 2026-09-20

**An engine handed a saga whose type matches no definition ran zero members across five phases and
wrote `COMPLETED`.** The caller was told the work succeeded, the money never moved, and every
assertion anyone naturally writes about the result passed. konekt's type constant reads `top_up`; the
definition, spelled by hand, read `topup`. Nothing in the model made the two meet, and the engine's
answer to "no member applies" was the same as its answer to "every member succeeded". Closed in
`72e0c49` — a saga no member of any phase applies to now fails terminally and names the types the
engine does know. **This is the second defect this stage owes to migrating a consumer rather than to
its own suite**, and the first that the suite could not have found: a test writes the type it
declared.

**The README hands one DDL to two stores that type the column differently.** `step_records TEXT NOT
NULL DEFAULT '{}'` is the native store's spelling; konekt is an Exposed consumer, where
`PetichTable` declares `json()`. Its schema guard caught the **default** and said nothing about the
**type** — Exposed's migration statements compare defaults, not types — and
`tools/schema-notes-audit.py` cannot catch it either, because it reads names from `PetichTable` and
compares declarations only between the two SQL-spelled sources. Filed as B-34 rather than folded in:
konekt is verified without it, and the payload columns carry the same divergence with eleven months
of shipped rows behind them.

**A consumer's house rules are part of what a migration costs.** konekt forbids `/* */` in production
sources, because its clock-usage guard strips line comments only and the ban is what keeps that
shortcut from rotting. The migrated file was written in KDoc and failed a test that has nothing to do
with sagas. Discoverable only by running the consumer's own suite — reading its code would not have
shown it.

**Done here:** konekt's top-up saga runs on the definition model (`TopUpSteps.kt` replaces
`TopUpInterceptors.kt`), `Credited : PetichStepRecord` replaces the ledger lookup behind
youndie/konekt#48, `ValidateTopUp` is a `PetichCheck` with no `compensate` to write, `AnnounceTopUp`
uses `ctx.emit`, and V13 migrates the four 0.3.0/0.4.0 columns. konekt's full build is green — 47
test classes on `:server` alone.

**First thing next iteration, before any new work:** `72e0c49` is a correctness fix every consumer
needs and it is parked on this long-lived branch, so every later item branches from a `main` without
it. It touches only `Petich.kt`, `DefinitionEngineTest.kt` and the research — cherry-pick it onto its
own branch, merge it on green, and rebase this one. Holding a shipped fix hostage to an unfinished
migration is the cost of having committed it here.

**Next:** konekt's purchase saga, whose `HoldFundsInterceptor` holds money and suspends in one step —
the member the new types have to earn their keep on. Then its tariff saga, then shashki, whose tree
was still carrying another session's work at the start of this iteration.
