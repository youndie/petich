---
id: B-42
title: "petichDefinition<T>() returns a definition and Petich is an instance"
status: wip
priority: P2
size: S
stage: stage-10-review
blocked_by: []
---

# B-42 — one word for two concepts, and the tests found it first

`petichDefinition<OrderPayload>("order") { … }` returns a `PetichDefinition`. `Petich` in a member's signature
is the **instance** — a row with an id, a status and a version. Two different things under one word,
and D6 chose it deliberately: the vocabulary is `Petich*` and "saga" stays in prose.

- **The cost is already paid and measurable.** Migrating the suite off the interceptor model (B-33)
  meant renaming a local helper in **thirteen** test files, because each had a private
  `fun petich(id: String): Petich` that shadowed the builder the moment the file needed one. They are
  `row(id)` now. Every consumer writing a fixture will meet the same collision.
- **The prose pays it too**: the README and the research have to say which sense is meant each time
  the word appears near a signature.
- **The options are narrow and should be decided rather than left**: `saga<T>("order") { … }` for the
  builder — which reopens D6 and is the reviewer's suggestion — or `petichDefinition<T>(…)`, which
  keeps the vocabulary and costs the reading.

## Acceptance

- The builder and the instance do not share a name, or D6 is restated with this cost recorded and the
  decision made knowingly.
- If the builder is renamed, both consumers move with it.
