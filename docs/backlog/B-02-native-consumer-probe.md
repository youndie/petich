---
id: B-02
title: "A linuxX64 consumer project that must fail today"
status: wip
priority: P0
size: S
stage: stage-0-gate
---

# B-02 — The negative control for the whole port

Everything in this backlog is accepted by the same sentence — "a Kotlin/Native consumer can take
petich" — and nothing in this repository can say it. The build compiles, the tests pass and the
publication succeeds today, with a library no native consumer can resolve at all
([research §1.1](../research/research-native-port.md)).

- **Raised to P0 on 2026-09-16, before it was picked.** It went in as P1 beside a P0 it blocks,
  which reads as "do the P0 first" — and doing so destroys this item: the control being written is
  that the probe *refuses* to resolve, and [B-03](B-03-linux-target-on-the-portable-four.md) is the
  change that makes it resolve. A negative control has one window, and it closes when the fix lands.

- **The probe is written first and must fail first.** A throwaway `linuxX64` project that declares
  the petich coordinates from `mavenLocal`, calls the engine and links. Run against today's
  publication it must fail in resolution with *no matching variant*; if it passes now, it is
  testing something other than what it claims, and every later green run means nothing.
- **Rejected: reading `.module` metadata as the acceptance.** Metadata says a variant was published,
  not that a consumer can take it — attributes can be present and still not match. Metadata stays as
  a second opinion, not as the answer (research D7).
- **Does not cover:** running anything. Linking is the bar for stages 1–2; executing a saga against a
  real store is [B-09](B-09-native-store-module.md)'s acceptance.

- AC: on today's `main`, the probe fails with *no matching variant* for
  `io.github.youndie.petich:petich-core`; the failure text is quoted in the item when it is closed,
  so the later pass has something to be compared against.
- Anchors: `tools/` (where the probe's runner belongs, beside the other three audits),
  `.github/workflows/publish-snapshot.yaml`

