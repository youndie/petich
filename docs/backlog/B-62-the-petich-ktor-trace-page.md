---
id: B-62
title: "Is the definition with one saga's path drawn over it worth a page, and no more than a page?"
status: question
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
