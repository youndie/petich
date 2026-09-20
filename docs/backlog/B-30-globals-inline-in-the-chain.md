---
id: B-30
title: "A cross-cutting check must be visible where the saga is read"
status: done
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

## Findings — 2026-09-20

**The open question is answered: a global is a `PetichCheck` and cannot be a `PetichStep`** (research
D9). A member that acts has to be undone, and a global's undo would run inside every saga's rollback
at a position no saga's author wrote — the defect this stage removes, arriving from the other side.
Stated honestly, the rule is not "a global is pure": it may act through its own ports, and what the
type forbids is acting in a way that leaves petich owing somebody an undo. It also cannot announce,
`emit` having moved to the step context for the same reason (B-35).

**One line in `chainFor`, and that was the whole design.** Every question about a chain — what runs,
in what order, what the fingerprint covers, what a mismatch prints — already went through that single
function. Globals go in front of their phase's declared members there, so `describeChain` renders
them inline and the fingerprint covers them **by construction**, not by four call sites remembering
to. The rejected alternative, a table of globals beside each chain, is a hand-written list next to a
growing set.

**A defect found on the way, and the item could not be verified without fixing it.** `describeChain`
took a *payload* and resolved the chain by it; a definition is resolved by *type*. Every saga on the
definition model therefore had its **interceptor** chain described — the empty one — including inside
the message a chain mismatch prints, whose entire job is to tell a reader what the chain is. The
diagnostic built for this stage printed five dashes to the people the stage is for. Now defaulted
`type` parameter; the interceptor model keeps its behaviour.

**Keys are one namespace, refused at construction.** A key identifies a member in the saga's row and
a global shares its chain with every definition, so two members under one key would overwrite each
other's record and make the fingerprint ambiguous.

**Five cases, three mutations, each firing what it should:**

| what was broken | what failed |
| --- | --- |
| globals stop entering the definition chain | four of five |
| `describeChain` forgets the type again | only the rendering case |
| the key-collision guard stops guarding | only the construction case |

The second is the one worth noting: it fires **alone**, so the `describeChain` repair is guarded on
its own rather than incidentally by the tests about globals.

- AC — a global appears in `describeChain` for every saga it applies to, in the position it runs:
  covered by *a global is written into the chain where the saga is read*.
- AC — adding one changes the fingerprint, proved by a test that resumes a saga across the change:
  covered by *a global added before a suspended saga's position stops it rather than moving it*,
  which suspends a saga, deploys the global into a phase already walked, and resumes.
