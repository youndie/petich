---
id: B-45
title: "The README describes the model as it was before stage-10"
status: wip
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
