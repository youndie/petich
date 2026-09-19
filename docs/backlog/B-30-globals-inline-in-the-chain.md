---
id: B-30
title: "A cross-cutting check must be visible where the saga is read"
status: open
priority: P2
size: M
stage: stage-9-definition
blocked_by: [B-28]
---

# B-30 — the one thing the interceptor model was actually good at

Limits, audit and anti-fraud attach to every saga without editing each one. That is the real virtue
of a list filtered by `supports`, and a definition that spells its own order loses it unless
something replaces it.

The replacement is easy to get wrong in the exact way this whole stage exists to fix: a global mixed
in at a phase boundary is a member of the chain that is **not written where the saga is read**.

- **The decision:** globals are one declared, ordered list, and `describeChain` renders them
  **inline** in each saga's chain rather than in a table beside it. A reader of one saga sees what
  will run, including what was not written there.
- **The fingerprint covers them**, so a deploy that adds a global is caught by the same mechanism as
  a deploy that moves a step ([B-21](B-21-the-chain-is-addressed-by-position.md)). This is the half
  that is easy to forget and expensive to miss: a global inserted before the current position
  re-points every saga in flight.
- **Rejected: attaching globals per saga in the definition.** It is explicit, and it is a copy of the
  same three lines in every definition — which is what `supports` was for, badly.
- **Open, and named in the research:** whether a global may be a `PetichStep` at all, or only a
  `PetichCheck`. A global that acts has to be compensated in every saga's rollback, and no saga's
  author wrote it.

- AC: a global appears in `describeChain` for every saga it applies to, in the position it will run;
  adding one changes the fingerprint, proved by a test that resumes a saga across the change.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`, `docs/research/research-petich-dsl.md`
