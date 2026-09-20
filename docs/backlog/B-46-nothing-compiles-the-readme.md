---
id: B-46
title: "Nothing compiles the README, so its examples rot silently"
status: done
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

## Findings

**`tools/readme-probe` is a build of its own**, resolving petich from a publication and never from
`project(":petich-core")` — the same reason `native-consumer-probe` is one, applied one level out.
`tools/readme-examples.py` splices every ```kotlin block into it and compiles. Nine of the page's
eleven blocks are compiled; the two skipped are Gradle snippets.

**Placement is a marker, not a line number**, as the acceptance demanded — an HTML comment above the
fence saying `skip`, `statements` or `members`, defaulting to top level. A list of line numbers would
be a second thing to keep in step with the page, and it would rot the way the page did.

**The prelude is declarations only**, and the KDoc says why: the failure to avoid is not "the
examples do not compile" but "the examples compile against something that is not petich". A stub with
a body invites the harness to grow an API of its own, and then the page would be checked against a
fiction.

**The positive control works and its message is the one a reader needs.** Reverting `AnnounceOrder`
to its pre-B-41 shape — a `PetichStep` with `execute` — fails with
`Argument type mismatch: actual type is 'AnnounceOrder', but 'PetichAnnouncement<OrderPayload>' was
expected`, which names the defect rather than the harness.

**It runs in `build.yaml`, not in `make check`, and that follows the Makefile's own stated rule**:
code checks live where the JDK is, so that a contributor editing a document needs neither a JDK nor a
Kotlin/Native toolchain download. `make help` names the local command, which is the acceptance's
"a reviewer can run it locally by name".

**Two corrections the harness forced, both about the harness rather than the page.** A `members`
block is spliced into an `open class` rather than an interface, because the page shows one half of a
step twice and an interface would fail those for a reason that is about this check — the one kind of
red it must never produce. And `PaymentGateway` gained `release(id)`, which B-48's replay example
calls and the prelude had not declared.

**The mac/Linux split cost a wrong diagnosis before it cost a fix.** The assembled file lives in the
working tree, and this portfolio synchronises the tree one way onto the build host — so generating on
the host and then synchronising again overwrote the fresh file with the stale copy from the other
side, and the run looked as though the markers were being ignored. `--emit-only` separates the two
halves: emit where you edit, compile where you build. The reason is in the flag's help rather than in
anybody's memory.
