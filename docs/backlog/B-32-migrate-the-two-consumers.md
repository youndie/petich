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

**Done 2026-09-20:** that fix is out of this branch and on `main` as `383068c` (#78), so every later
item branches from a `main` that has it. Its test was re-checked by mutation on the way out — guard
disabled, `DefinitionEngineTest` reported `type=ordr, status=COMPLETED`, the defect verbatim. The
lesson to keep is the one that put it here: a correctness fix committed onto a long-lived migration
branch is a fix nobody else gets until the migration finishes.

**Next:** konekt's purchase saga, whose `HoldFundsInterceptor` holds money and suspends in one step —
the member the new types have to earn their keep on. Then its tariff saga, then shashki, whose tree
was still carrying another session's work at the start of this iteration.

## Iteration 3 — 2026-09-20

**Correction to Iteration 2: konekt had already found the unknown-type defect, written it down twice,
and engineered around it.** Its composition root says, of two engines over one saga table, that petich
"resolves nothing by type itself — an engine is a fixed interceptor list — so handing a top-up to the
purchase engine finds no step that supports its payload, completes a saga that did nothing, and
reports success", and `PurchaseModule` repeats it for the Koin qualifier. Reporting it as *found by
migrating a consumer* was flattering and wrong: the consumer found it, described it precisely, and
paid for a workaround, and the library never learned. What the migration did was remove the
workaround. **B-31 is that workaround's removal**, and its justification is sitting in konekt's wiring
comment rather than in this backlog.

**The purchase saga is migrated, and the member it was filed for held up.** `HoldFunds` acts and then
suspends in one member — `authorize` takes a step as well as a check precisely for it (D3, which
names konekt) — and the reversal it announces moved from an overridden `compensateWithEvents` to
`ctx.emit` inside `compensate`. No type had to be bent and the member was not cut in two.

**B-29's record was verified through a path petich's own suite cannot reach.** The hold records
`Held` before suspending; its undo runs after a resume, in a different process-lifetime, having
crossed the database. Mutation: delete `ctx.record(Held(…))` and two tests fail — *a purchase nobody
confirms is rolled back and the balance returns* and *a declined provider rolls the purchase back and
the screen says why*. Those are the suspend→expire→compensate and suspend→confirm→fail→compensate
paths. A record that survives a suspension is the claim B-29 makes; this is the first time anything
ran it.

**A new hole in the model, filed as B-35.** `RecordingContext.outcome()` carries events on `Proceed`
and drops them on `Suspend`, `Reject` and `Compensate`. The old model could not express an event on a
suspending step — `InterceptorResult.Suspend` has no field for one — so the new model turned a
missing capability into a silent drop. konekt's purchase validation writes its refusal through its own
ledger port and so is unaffected; the comment there now says why, because `ctx.emit` is the reading
anybody would reach for next.

**A behaviour change, stated rather than smuggled:** `Provision` records `Provisioned` and its undo
revokes only against it. Before, a rollback interrupted between the capture and the grant revoked an
allowance nobody had added. That is konekt#48's shape at a different member, and it is a change to
what konekt does, not a rewrite of how it says it.

**Left:** konekt's tariff-change saga, then shashki. konekt's petich pin is still `0.4.0.70`, which
predates #78 — its green run does not exercise the unknown-type refusal, and nothing here needs it to.

## Iteration 4 — 2026-09-20

**konekt is done, and holds no reference to the interceptor model at all.** The tariff-change saga was
the last of its three; two test doubles moved with it, so `PetichInterceptor` and `InterceptorResult`
now appear nowhere in the repository. `./gradlew build --rerun-tasks` green — 47 test classes on
`:server`, 7 on purchases, every task genuinely executed rather than replayed.

**The model does not ask every acting member for a record, and the tariff saga is the proof.** The
purchase releases money and the top-up reverses a credit, and both are wrong when they run against
work that never happened — so both record. Both tariff compensations are `changes.cancel(id)` against
a row keyed by the saga's own id: no row, no update, no harm. A record there would have been ceremony,
and writing one because the other two have one is how a model's vocabulary turns into a ritual. The
question a record answers is *can this undo tell?* — not *did this member act?*

**A comment corrected, not a behaviour.** The applying member's undo read "back to pending rather
than to nothing … the step before this one owns the withdrawal", while the code called `cancel` —
and `TariffChanges` has no operation that returns a row to pending. The sentence described a design
nobody had built. Behaviour left exactly as it was, because it is right; the prose now says what runs.
Found only because migrating forces every comment to be re-read beside its code.

**Verified through the real path:** mutation on `RecordTariffChange.compensate` — blanked, and *an
unconfirmed change past its deadline leaves the current tariff untouched* failed, which is the
suspend→expire→compensate path and B-21's own acceptance criterion. Restored, tree clean.

**shashki has been blocked by the same thing for four iterations:** its tree carries another session's
uncommitted work on `build/take-sborka-0.4.0.84` (`gradle/libs.versions.toml`, `settings.gradle.kts`).
This is not a shortage of time and a fifth attempt will not change it — **it needs a person to land or
drop that work.** Everything else in this item is finished, so what remains of B-32 is shashki and
nothing else.
