---
id: B-58
title: "Nothing reports what one saga did, only how many of a type did it"
status: open
priority: P1
size: L
stage: stage-12-tracer
blocked_by: []
---

# B-58 — a per-saga event hook: `PetichTracer`

The row is a position, not a history, and every reporting channel the engine has is keyed by saga
**type** — `PetichEngineMetrics` says "Deliberately no saga id" in its own KDoc. Member entry and
exit, a resume, and each undone step reach nobody at all; claims reach the sweeper's callbacks and
stop there. The three defects of the last review round were found by reading code and by doubles
that count calls, and none of them would have been visible in a running system. The argument, the
fixtures and the kill criteria are in
[research-petich-tracer](../research/research-petich-tracer.md) — this item is its RQ1.

- **A hook, not a surface.** `PetichTracer` (a `fun interface`, `NoOp` default) and a sealed
  `PetichTraceEvent` in `petich-core/commonMain`: saga id, type, member key, phase, attempt, a
  `PetichClock` timestamp, and the outcome. No UI, no exporter — the value of this item does not
  depend on any surface existing.
- **A constructor parameter, wrapped once.** B-57's rule puts anything the engine calls into or
  guards among the constructor's collaborators; a `GuardedTracer` sits beside `GuardedMetrics` and
  uses the same `guarding`, so a tracer that throws changes nothing about the saga.
- **One helper for tracer and metrics** at the sites that already call metrics, so an event added to
  one cannot be forgotten in the other (research, Risk 1).
- **The sweeper takes the tracer too.** Claims won and lost happen there, and "claimed and revived"
  is the event H1 needs. It has no metrics parameter today; it gets the tracer only.
- **No replica label in the event.** Nothing in the engine knows which replica it is; a tracer
  instance is per process, so the sink stamps it.
- **Rejected: a tracer that reads the row.** The row holds the latest values only, and anything the
  tracer needs that is not on the stack at an emit site is a store feature — which is RQ1's red, not
  a workaround.
- **Not covered:** any exporter (B-61), any sink beyond `NoOp` and a test recorder, any consumer
  wiring (B-60), any fix to what the fixtures show (B-59).

## Acceptance

- Every event listed in the research's RQ1 green is emitted from `petich-core`: pass started /
  retried; member entered / proceeded / rejected / failed / timed out / suspended / re-suspended;
  announcement failed; claim won / lost on either queue; rollback started (from index, terminal
  status); step undone; rollback gave up / exhausted; chain refused; chain unavailable.
- **No change** to `PetichRepository`, the conformance corpus, or the counts pinned by
  `WriteCountTest` — the test is not edited.
- A test enumerates the sealed event type and asserts each variant is emitted by at least one
  scenario; a variant nothing emits fails it.
- A tracer that throws on every event: every saga suite outcome is unchanged. A tracer that blocks:
  the contract that it must return immediately is written on the interface, and a test shows what
  blocking costs rather than hiding it.
- **The three fixture pairs** of research §1.4 each produce two traces that differ in the events
  that name the difference — (a) the number of `member entered` for the announcement and the retried
  passes that caused them; (b) `announcement failed` followed by `COMPLETED` against a `rollback
  started`; (c) `rollback started` with terminal `REJECTED` against terminal `FAILED`.
- **H1:** a pass killed inside a member reads as *entered, [nothing], claimed and revived by the
  sweeper*.
- Events carry no payload and no untruncated free text (Risk 3).
- **H2:** `petich-core/build.gradle.kts` still names two main dependencies.
- README: one paragraph and the constructor parameter, compiled by `readme-examples.py`.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonMain/kotlin/Guarded.kt`,
  `petich-core/src/commonMain/kotlin/PetichEngineMetrics.kt`,
  `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
  `petich-core/src/commonTest/kotlin/WriteCountTest.kt`,
  `petich-core/src/commonTest/kotlin/AnnouncementRunsAgainTest.kt`,
  `petich-core/src/commonTest/kotlin/ForeignCodeCannotDecideTest.kt`,
  `petich-core/src/commonTest/kotlin/RollbackKnowsItsEndingTest.kt`
