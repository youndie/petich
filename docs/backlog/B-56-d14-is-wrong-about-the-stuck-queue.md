---
id: B-56
title: "D14 says nothing is written, and on the stuck queue something is"
status: wip
priority: P3
size: S
stage: stage-11-review
blocked_by: []
---

# B-56 — the text, not the behaviour

D14 and the README's runbook say a saga refused for a changed chain is **left exactly as it was** and
that the counter therefore fires on every pass. On the forward path that is true. On the stranded
queue it is not: `sweepStuck` takes its claim — one write that bumps the version and re-stamps
`updated_at` — **before** `chainMismatch` refuses. So the row moves, and it stops matching the "not
touched since" predicate that found it, which means the refusal is reported about once per
`stuckAfter` rather than once per pass.

The behaviour is harmless and arguably better: a row that hides itself for a while is a row that does
not fill a log. What is wrong is the two sentences that describe it, and they are the ones a person
reads while deciding whether an alert is firing as often as it should.

## Acceptance

- D14 and the README say what actually happens on each of the two paths, including the rate.
- If the once-per-`stuckAfter` rate is the wanted one, it is stated as intent rather than left as a
  consequence of where the claim sits.
