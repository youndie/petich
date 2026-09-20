---
id: B-41
title: "announce takes a full step, and its failure has no defensible meaning"
status: done
priority: P2
size: M
stage: stage-10-review
blocked_by: []
---

# B-41 — by the time it announces, the work is done

`announce(key, step: PetichStep<P>)` takes a member that can `fail`, `reject`, `suspendFor` and be
compensated. By the time it runs the stock is reserved and the money is captured. Rolling the saga
back because a notification did not go is almost certainly the wrong answer, and the type offers it
as the natural one.

With a transactional outbox the question should not arise: an announcement records an intent in the
same write as the final state, so it can only fail with that commit. A type of its own — returning
the event rather than taking a context — would say that a notification cannot be undone and cannot
fail the saga.

- **The portfolio has one member that would not fit as stated, and it is the interesting one.**
  shashki's `PublishSettledStep` sends a receipt by mail before emitting. It is I/O in an
  announcement, and it is deliberate: the send's failure is swallowed by hand and the outcome written
  into the enriched payload, because `SendReceiptUseCase` says outright that a settlement rolled back
  over a mail server would be the tail wagging the dog. So the rule the reviewer proposes is already
  the rule that member follows — **by discipline, not by type**, which is the same gap as B-39.
- **Which means the design question is not "can an announcement do I/O" but "can it fail the saga".**
  A type returning only an event answers both at once and is too strong for shashki; a type that may
  act but has no `fail`, no `reject` and no `compensate` answers the one that matters.
- konekt's two announcing members are `ctx.emit` and nothing else, so they fit either shape.

## Acceptance

- An announcing member cannot fail the saga, refuse it, or be compensated — checked by the type.
- shashki's receipt keeps working without swallowing anything by hand, or the reason it must is
  stated where its member is declared.
- Whether such a member may suspend is answered too: nothing in the portfolio does, and "no" is
  cheaper to relax later than to impose.

## Findings

**Acceptance, in order.** `PetichAnnouncement<P>` has one method and no `compensate`;
`PetichAnnouncementContext` has `emit`, `attach` and `enrich` and none of `reject`, `fail`,
`suspendFor` or `resuspendFor`. Whether such a member may suspend is answered by that absence: no,
and nothing in the portfolio wanted to.

**The type was the smaller half, and that is the correction.** Withholding `fail` stops a member from
*deciding* to end the saga and does nothing about one that throws — and a throw meant exactly the
same rollback. Without the engine change the new type would have been a comment. An announcement's
exception is now counted through `onAnnouncementFailed` and the saga completes; what it asked to have
committed before throwing rides with that completion, which departs from B-35 deliberately (a refusal
carries nothing because it begins a rollback; there is no rollback here).

**The reviewer's shape was too strong, as the item suspected.** A type returning the event and taking
no context answers "may an announcement do I/O" as well as "may it fail the saga". shashki's receipt
is the counter-example to the first and is not a mistake. The context keeps `emit` and `enrich`.

**shashki's hand-swallowing stays, and its reason has changed — stated at the declaration.** It used
to be the only thing between a dead mail relay and a refunded fare. That is the type's job now. What
survives is the reason a type cannot remove: **a member that dies cannot write down that it died**,
and `Settled.RECEIPT` is how a ride whose receipt never went is found afterwards. The `getOrElse` is
bookkeeping, not defence.

**The cost was measured in a consumer's suite asserting the opposite.** shashki's
`dying after any phase leaves no held payment and no reserved driver` ran the same death through four
members including `publish-assigned`, and asserted that a death there **released the fare and freed
the driver** — a ride un-assigned because the sentence announcing it could not be built. The
settlement suite had the identical pair. Both announcing members came out of those lists and got
cases of their own with the opposite assertion.

**A second labelling site was found by a test rather than by design.** `PetichMember`'s kind was
spelled both in the engine's chain dump and in `PetichDefinition.describeChain`. Adding the
announcement to one left the other calling every announcement a check. Both now read one `kind`.

**The main build does not compile every consumer petich ships.** `./gradlew build` was green and CI
was not: `tools/native-consumer-probe`, a Kotlin/Native program built against the *published*
artefacts rather than against the source, still declared its announcing member as a `PetichStep`. It
lives outside the Gradle build on purpose — that is what makes it a consumer — and the cost is that
the only thing which compiles it is a CI step. Worth knowing before the next change to a public type:
the local signal for that step is `python3 tools/native-consumer-probe.py <version> --expect resolve`
against a `publishToMavenLocal`, and it takes half a minute.

**Verification.** Full build on the Linux box, 413 tests across `jvmTest`, `linuxX64Test` and `test`,
result files checked for freshness. The engine change was mutated after being committed — letting the
announcement's exception propagate fails three of the five new cases. The consumers were built
against a locally published petich, with a positive control: the same mutation, republished, fails
exactly the two new consumer cases and nothing else.

## Consumers

Branches are pushed and their pull requests open once main has published the snapshot that carries
the type — `youndie/konekt` and `youndie/shashki`, both `feat/announcement-cannot-fail`.
