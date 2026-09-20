---
id: B-42
title: "petichDefinition<T>() returns a definition and Petich is an instance"
status: done
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

## Findings

**The builder is `petichDefinition(…)`.** The instance keeps `Petich` and `ctx.petich`; the function
is named for what it returns.

**Renaming rather than restating D6 follows from the code, which is why this did not become a
question.** Three things, none of them taste:

- **D6's reason is scoped to types** — "every type in the library is already `Petich*`, and a lone
  `SagaStep` would be the exception". A top-level function is not a type, so D6 never argued about
  this identifier at all.
- **`petich` was the library's only bare public function.** Every other public function in the
  published surface is an extension (`Route.petichRouting`, `Petich.toResponse`,
  `PetichStatus.isTerminal`, `PetichDecidingContext.recorded`) or a `fun interface`, which is a type.
  So a lowercase `petich` in this package meant the package or the instance — `ctx.petich` hands the
  instance to every member — and the builder was the exception to that too.
- **D5 had already written the objection down and it was not followed**: *"A function `petich(…)`
  beside a class `Petich` is legal Kotlin and would read badly if the result were not named for what
  it is."* It shipped that way, and the cost arrived exactly where that sentence predicted.

**The measured cost was larger than the item recorded.** Thirteen test files is the number B-33 paid;
**three more private `fun petich(id): Petich` helpers survive** in `petich-postgres`,
`petich-sqlx4k-postgres` and `petich-conformance`, in modules whose tests never needed a definition
and therefore never collided. They are correct and stay — but they are why the collision looked
smaller than it is, and they are three loaded guns for the next test in those modules that wants a
definition. A blind substitution would have renamed their call sites; the sweep was scoped to the
generic form and the rest reviewed by hand for that reason.

**`saga<T>(…)` is left open as a person's decision, with its price measured rather than estimated.**
It puts the domain word into an identifier, which is precisely what D6 settled, so it is not this
rename's to make. Switching later is a substitution over about 110 call sites plus a version bump in
two consumers — which is what this rename actually cost, so the number is a measurement.

**There is nothing to mutate, and the compiler is the test.** A rename has no behaviour; what could
go wrong is a call site left behind or a deprecated alias keeping the old name quietly alive. The
control for both is the same: reverting one consumer call site to `petich(` fails with
`Unresolved reference 'petich'`, so no alias survives and every site moved or the build would not be
green.

**Verification.** Full `build --rerun-tasks` on the Linux box: 413 tests across `jvmTest`,
`linuxX64Test` and `test`, result-file freshness checked. `tools/native-consumer-probe` was run the
way CI runs it — it compiles against the published artefacts and `./gradlew build` does not touch it —
and links. Both consumers were built against a locally published petich and are green.

## Consumers

Branches pushed; their pull requests open once main has published the snapshot that carries the
rename — `youndie/konekt` and `youndie/shashki`, both `feat/builder-names-what-it-returns`.
