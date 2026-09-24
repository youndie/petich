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
