---
id: B-62
title: "Is the definition with one saga's path drawn over it worth a page, and no more than a page?"
status: dropped
priority: P3
size: M
stage: stage-12-tracer
blocked_by: [B-61]
---

# B-62 — the `petich-ktor` page, and RQ3

Opened only by an RQ2 red in [B-61](B-61-petich-trace-otel.md). See RQ3 in
[research-petich-tracer](../research/research-petich-tracer.md).

- A route in `petich-ktor` that renders `describeChain` as lanes and one saga's events over it as
  inline SVG, served by the service itself. Recorded chain versus current chain (B-44) as a diff on
  the same picture.
- **Unverified and decided here:** where the page reads one saga's events from after the fact — the
  hook is best-effort and in memory by contract.

## Acceptance

- RQ3 green: the overlay shows the pair(s) RQ2 could not, with no state beyond the page's query and
  no script tag (H3).
- RQ3 red: the page needs live updates, interaction or storage — this item is `dropped`, and B-63 is
  opened only if the in-process mode has a scenario nobody can debug headless (kill criterion 4).

- Anchors: `petich-ktor/src/commonMain/kotlin/PetichRouting.kt`

## Dropped 2026-10-07 — its gate cannot be reached

[B-60](B-60-a-logging-sink-in-konekt-and-shashki.md) closed as **not measured**: konekt and shashki
are demonstration services, ran no sagas in the two weeks, and kill criterion 2 neither fired nor
passed. This item was gated on that verdict, and nothing in this repository can produce it.

**Reopen when** a consumer runs real sagas in production with `LinePetichTracer` (or any
`PetichTracer`) wired, and B-60's question — did reading a trace answer something a counting double
had not — has been asked of its logs. Until then the line of work stops at the hook.
