---
id: B-46
title: "Nothing compiles the README, so its examples rot silently"
status: wip
priority: P2
size: M
stage: stage-10-review
blocked_by: []
---

# B-46 — the page is a consumer, and it is the only one nothing builds

B-45 fixed eight places where the README described a model the code no longer had. Every one of them
survived for the same reason: **prose beside code is checked by nobody.** The gate runs
`backlog_index.py`, `code_anchors.py` and `make check`; none of them compiles a fenced `kotlin` block.

The sharpest case is the one that shows it is not carelessness. *"`step` and `announce` take members
that act"* was **true on the day it was written** and became false when B-41 merged — four hours
before a reader found it. No amount of care at the keyboard catches that; only a compiler does.

The repository already accepts this argument once. `tools/native-consumer-probe` exists because
`./gradlew build` does not compile what a consumer compiles, and it earned its keep during B-41 by
turning CI red on a green local build. The README's examples are a consumer too — arguably the first
one every user meets.

## Acceptance

- The `kotlin` blocks in `README.md` that are meant to be real code are compiled against the
  published or locally built artefacts, and a block that stops compiling fails a check.
- Blocks that are deliberately fragments — `ctx.suspendFor("CONFIRM", ttl = 5.minutes)` on its own,
  a `dependencies { }` snippet — are excluded by a marker rather than by a list of line numbers,
  because a list of line numbers is a second thing to keep in step.
- The illustrative types the examples lean on (`StockRepository`, `PaymentGateway`, `OrderEvents`)
  live somewhere the check can see. Inventing them in the harness is acceptable; inventing a
  different API from the real one is the failure mode to avoid, so they are declarations only.
- It runs where the rest of the gate runs, and a reviewer can run it locally by name.
- **A positive control**: reverting one example to the pre-B-41 shape must turn the check red.
