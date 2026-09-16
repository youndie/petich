---
id: B-02
title: "A linuxX64 consumer project that must fail today"
status: done
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

## Closed 2026-09-16

`tools/native-consumer-probe/` is a Gradle build of its own — one `linuxX64` executable that names
the engine's types in its own source — and `tools/native-consumer-probe.py` runs it against a local
publication and classifies the outcome. Run against this repository as it stands:

```
> No matching variant of io.github.youndie.petich:petich-core:probe-b02 was found. The consumer was
  configured to find a library for use during 'kotlin-api', preferably optimized for non-jvm, as
  well as attribute 'org.jetbrains.kotlin.klib.packaging' with value 'non-packed', attribute
  'org.jetbrains.kotlin.native.target' with value 'linux_x64', attribute
  'org.jetbrains.kotlin.platform.type' with value 'native' but:
BUILD FAILED in 8s

REFUSED: io.github.youndie.petich:petich-core has no variant this target can use
```

That is the acceptance: the failure is about a missing *variant*, in the consumer's build, with
nothing in it about petich being unfinished.

**Both controls were run, because a probe that can only say one thing says nothing.**

*The negative one.* A version that was never published resolves to `Could not find …` and is
classified `NOT PUBLISHED`, not `REFUSED` — so a run that silently failed to publish cannot be read
as the answer this item is about. Without that branch, `--expect refusal` would have passed against
an empty repository.

*The positive one.* `linuxX64()` was added to `petich-core` temporarily, published as
`probe-b02-native`, and the same probe reported `RESOLVED` and linked
`native-consumer-probe.kexe`; the binary was then executed on the Linux box and printed
`petich resolved, compiled and linked for linuxX64: probe phase=ENRICHMENT terminal=false
enrichmentTimeoutMs=1000 clock=0`. The temporary line was reverted — declaring targets is
[B-03](B-03-linux-target-on-the-portable-four.md) — but the run answers the question this probe
would otherwise beg: the `RESOLVED` branch is reachable, and the freshness check on the linked file
is what distinguishes it from a binary left by an earlier run.

**A finding for [B-03](B-03-linux-target-on-the-portable-four.md), gathered on the way.**
`petich-core`'s `commonMain` compiled for `linuxX64` with no source change of any kind, and a
consumer linked and ran against the klib. What that does *not* say is anything about
`linuxX64Test`: the two things chronik's identical change found — a `kotlin.jvm` default import and
a comma in a backticked test name — both live in test sources, which a publication never compiles.

**Where it runs.** On a Linux x64 host with the Kotlin/Native toolchain; here, the WSL box, through
`~/.claude/bin/wsl-run`. Linking a `linuxX64` executable is the question being asked, so there is no
useful way to run this on the mac. It is deliberately not wired into CI —
[B-12](B-12-guards-meet-the-native-variants.md) owns the question of what checks the native half of
a publication, and wiring a probe into a workflow before there is anything for it to resolve would
be a green check over an empty subject.
