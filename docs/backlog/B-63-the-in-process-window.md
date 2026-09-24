---
id: B-63
title: "Does a Compose window that a test opens show a fault-injection scenario a headless run cannot?"
status: question
priority: P3
size: M
stage: stage-12-tracer
blocked_by: [B-62]
---

# B-63 — the in-process window, and RQ4

Opened only by an RQ3 red in [B-62](B-62-the-petich-ktor-trace-page.md), and only if a scenario
exists that nobody can debug headless. See RQ4 and kill criterion 5 in
[research-petich-tracer](../research/research-petich-tracer.md): a window a test opens is in, an app
that is installed is out.

## Acceptance

- RQ4 green: a fault-injection scenario (kill inside a member, contended claim, TTL expiry on a
  cascade) is readable live in under a minute by someone who has not seen the engine, and is also an
  ordinary test that passes headless.
- RQ4 red: it needs a data path the tests do not have, or shows only what the assertion prints — then
  it is a demo, and the item is `dropped`.

- Anchors: `petich-core/src/commonTest/kotlin/SuspendedTtlTest.kt`
