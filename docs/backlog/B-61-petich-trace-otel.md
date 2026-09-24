---
id: B-61
title: "Do plain OpenTelemetry spans show the three fixture pairs without a surface of our own?"
status: question
priority: P2
size: M
stage: stage-12-tracer
blocked_by: [B-60]
---

# B-61 — `petich-trace-otel`, and RQ2

Opened only if kill criterion 2 did **not** fire at the end of
[B-60](B-60-a-logging-sink-in-konekt-and-shashki.md): a trace answered something a counting double
had not. Until then it is a question, not a task. See RQ2 in
[research-petich-tracer](../research/research-petich-tracer.md).

- A module of its own, so `petich-core` stays at two dependencies: saga as trace, member as span,
  retry as a repeated span, rollback as a span whose children are the undone steps, claim outcomes as
  span events on the sweeper. The exporter buffers; the tracer contract says return immediately.
- Judged on captured exports of the three fixture pairs, rendered in a stock Jaeger or Tempo UI.

## Acceptance

- RQ2 green: a person shown only the waterfall answers the three questions of RQ2, three of three —
  then kill criterion 3 fires, and B-62 and B-63 are `dropped` with this item as the reason.
- RQ2 red on any pair: the pair and the question the waterfall could not answer are written down, and
  B-62 becomes `open`.

- Anchors: `gradle/libs.versions.toml`, `settings.gradle.kts`
