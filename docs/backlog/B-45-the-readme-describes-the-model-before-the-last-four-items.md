---
id: B-45
title: "The README describes the model as it was before stage-10"
status: done
priority: P2
size: S
stage: stage-10-review
blocked_by: []
---

# B-45 — thirteen places where the page and the code disagree

Found by a reader working from the README alone. Every one checked against the code; none is a
matter of taste.

**Wrong about behaviour**

1. *"`step` and `announce` take members that act"* — `announce` takes a `PetichAnnouncement` since
   B-41: no `compensate`, no `fail`, and an exception counted rather than rolled back. The page
   describes exactly the behaviour B-41 removed, and `AnnounceOrder` is the only member in the
   example whose code is never shown, so a reader sees a verb and not its meaning.
2. *"steps inside a phase ordered by priority, and ties by name"* — `priority` left the model in B-28
   and exists nowhere in the library, globals included. It contradicts *"the members it runs, in the
   order they run"* four lines below.
3. *"nothing yet re-drives an abandoned saga into it (see the Cost section)"* — the Cost section says
   `SuspendedPetichSweeper` re-drives `COMPENSATING`. The pointer refutes the sentence that makes it.
4. The `Reject`/`Compensate` table is written in the outcome-value vocabulary, and `MemberOutcome` is
   `internal`: it documents a type a consumer cannot name. The API is `ctx.reject` and `ctx.fail`, and
   the table should say which members have which — `reject` is on `PetichDecidingContext`, so a check
   and a step both have it; `fail` is the step's alone.

**Wrong about numbers**

5. Two surviving *"eleven"*s — "re-TOASTed eleven times" and "changes on all eleven writes" — beside
   the eight the Cost section now publishes.

**Broken or misleading on the page**

6. The migration table is split by the `current_interceptor_index` paragraph, so the `0.3.0` and
   `0.4.0` rows lose their header and GitHub renders them as raw pipes.
7. Installation pins `0.2.0` while the text describes `0.3.0` and `0.4.0` columns and a
   `petichDefinition` that `0.2.0` does not have — so the example does not compile for anyone who
   copies the coordinates. One line saying the page tracks `main` and a release is its tag fixes it.
8. The repository description — About and `og:description` — still says *"interceptor pipeline"*.

## Acceptance

- Each of the eight is fixed or answered in the item, and 1 is fixed by showing the announcement's
  code rather than by rewording the sentence.
- The claim that a member has no priority is checked by grep across the library before the sentence
  is rewritten, not from memory.

## Findings

All eight fixed. The two the item singled out were done the way it insisted:

**1 — `announce` is shown, not reworded.** The page now names three kinds of member rather than two,
and `AnnounceOrder` has its code beside `ReserveStock`'s and `InStock`'s — it was the only member of
the example that did not. The paragraph under it says what the type withholds and what happens when
the body throws, because a reader who sees `PetichAnnouncement` and no `compensate` will ask.

**2 — `priority` was checked by grep before the sentence moved**, as the acceptance demanded: it
appears nowhere in any published source set, only in prose recording that it used to exist. The
replacement says what is true instead — the members of one phase run in the order the definition
declares, and a member carries neither a priority nor a phase of its own. Globals were checked too,
since "if priority survived for globals, say so" was the item's escape hatch: it did not.
`chainFor` puts them in front of a phase's declared members in the order they were registered.

**4 — the outcome table gained a column rather than a rewording.** It documented `MemberOutcome`,
which is `internal`, so it named a type a consumer cannot write. It now reads `ctx.reject` and
`ctx.fail` and says **who may call each**, which is the question the reviewer actually asked:
`reject` is on the context a check and a step share, `fail` is the step's alone, and an announcement
has neither.

**6 — the migration table was repaired by moving the prose, not the rows.** The
`current_interceptor_index` paragraph sat between two halves of one table, so GitHub rendered the
`0.3.0` and `0.4.0` rows as raw pipes. The paragraph now follows the whole table, where it reads as a
note on it rather than as a break in it.

**7 — the installation block says what it is.** `0.2.0` is the last release and the page describes
`main`; copying the coordinates and the examples together does not compile. Rather than bumping a
number the page cannot promise, it now names what arrived after `0.2.0` and says to take a snapshot
or read the README at the tag being pinned.

**8 — the repository description**, which is outside the repository and therefore outside every
check it runs. Was *"interceptor pipeline, compensation, suspend/resume and a transactional outbox"*;
is now *"a saga is a definition, with compensation, suspend/resume and a transactional outbox"*.

## What this did not fix, and it is the interesting one

**Nothing compiles the README.** Every one of these eight survived because prose beside code is
checked by nobody — and the reviewer found them by reading, which is the only instrument there is.
Item 1 in particular was correct on the day it was written and became false when B-41 merged, four
hours before the review.

That is a gap of the same shape as `tools/native-consumer-probe`, which exists precisely because
`./gradlew build` does not compile what a consumer compiles. The page's examples are a consumer too.
Not folded in here — it is a build change, not a docs change — and worth its own item.
