---
id: B-60
title: "Nobody reads a trace yet: a logging sink in the two consumers starts the two-week clock"
status: wip
priority: P2
size: S
stage: stage-12-tracer
blocked_by: [B-58]
---

# B-60 — a logging sink in konekt and shashki

A hook nobody reads settles nothing. Kill criterion 2 of
[research-petich-tracer](../research/research-petich-tracer.md) is the cheapest test the tracer
line has: ship the hook with a sink that writes one line per event into the two consumers' logs, use
them for two weeks, and ask whether any defect or question was answered by reading a trace that a
counting double had not already answered. If not, RQ2–RQ4 are not asked.

- **The sink lives in petich, the wiring in the consumer.** A `LinePetichTracer((String) -> Unit)` in
  `petich-core` formats an event as one stable line — saga id, type, event, member, phase, attempt,
  the sink's own replica label — and the consumer hands it its logger. `petich-core` takes no logging
  dependency; each consumer adds one constructor argument and nothing else.
- **A snapshot first.** Both consumers resolve petich snapshots from reposilite (konekt `0.4.0.88`,
  shashki `0.4.0.106`); the wiring needs a snapshot that carries B-58, and each bump is that
  repository's own PR.
- **The clock is written down.** The date each consumer's wiring merged goes into this item's
  Findings; the item closes at **merge + 14 days** with the evidence, not at merge.
- Rejected: a sink per consumer written in the consumer — two formats, and the next consumer writes a
  third.
- Not covered: any exporter, retention, or a surface.

## Acceptance

- `LinePetichTracer` in `petich-core` with a test that pins the line format.
- konekt and shashki wire it, each in its own PR on its own backlog, green there.
- **H5:** at merge + 14 days, the number of sagas each sink logged is recorded. Zero means the clock
  measured nothing and the item says so instead of firing kill criterion 2.
- The verdict of kill criterion 2 is written into the research document (§3) and B-61's status is
  set from it: `dropped` if it fired, `open` if a trace answered something.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`, `../konekt/gradle/libs.versions.toml`,
  `../shashki/gradle/libs.versions.toml`

## Findings

### Iteration 1 — 2026-09-24: the sink, in petich

`LinePetichTracer(replica, clock, write)` in `petich-core`: `petich.trace` followed by `key=value`
pairs — `ts`, `replica`, `saga`, `type`, `event`, then the event's own fields; a value with a space,
a quote or `=` is quoted and escaped. Pinned by `LinePetichTracerTest` (3/3 on jvm and linuxX64),
including a saga run end to end through the engine, so a change to the line is a failing test
before it is a log query that silently matches nothing.

**What the consumers' bump carries besides the sink.** shashki pins `0.4.0.106`, which already has
B-54's two columns; its bump brings B-58, B-59 and B-65 — behaviour only, and B-65's `PROCESSING`
mid-pass is a status its ride mapping already folds with `DRAFT`. konekt pins `0.4.0.88`, **before**
B-54, so its bump also needs the two `compensating_*` columns in a migration of its own — the same
step shashki took as its B-96. That is konekt's item to file, and it is filed there with the wiring.

The item stays `wip`: it closes at the consumers' merge + 14 days.

### Iteration 2 — 2026-09-24: the wiring, and the clock

- **shashki** — youndie/shashki#32 (its B-98), merged **2026-09-24T19:43:48Z**: `petich = 0.4.0.112`,
  a `LinePetichTracer` on logger `shashki.saga`, replica `HOSTNAME`. No column; its `ApplicationTest`
  log shows the server writing a settlement saga event by event through the real Koin graph.
- **konekt** — youndie/konekt#53 (its B-128), merged **2026-09-24T19:43:53Z**: `0.4.0.88 →
  0.4.0.112`, V14 for B-54's two columns (positive control: `KonektSchemaTest` names both without
  it), a `LinePetichTracer` on logger `io.konekt.saga` with kore's instance name. **The PR's e2e
  stand logged 202 `petich.trace` lines** from the server container — the real path, not a test.

**The clock of kill criterion 2 runs to 2026-10-08.** On that date this item records, per consumer,
the number of distinct sagas its log carries (H5), and whether any defect or question was answered
by reading them that a counting double had not. Two cautions written now so they are not argued
then: *merged is not deployed* — the count is taken from whatever actually runs these images, and a
consumer that ran nothing in the fortnight measured nothing; and a stand run by CI is not "ordinary
use", so its 202 lines prove the wiring and count for nothing in H5.

**Found on the way, in shashki:** `RefusingMetrics.onDroppedEvents` throws on purpose, and petich
B-52's `GuardedMetrics` swallows that throw silently — so the loud failure that consumer relies on has
been quiet since `0.4.0.106`. Filed there as shashki B-99; it is petich's guard behaving as documented,
and a consumer's comment describing behaviour the engine no longer has.
