---
id: B-27
title: "0.3.0 goes to Central before the redesign starts"
status: dropped
priority: P0
size: XS
stage: stage-9-definition
---

# B-27 — do not leave five fixes unpublished behind a rewrite

0.3.0 is merged, rehearsed against both consumers and published as a snapshot; the Publish button on
Central has not been pressed. The redesign in this stage removes the model those fixes were made in,
so anything unreleased now stays unreleased for as long as the rewrite takes.

- **The published version should not be the one with the defects.** 0.2.0 is what Central serves, and
  in it a failed step is never compensated, a refusal keeps what earlier steps did, and a rollback
  that cannot finish has no end. Those are the five items of stages 6 and 7.
- **It is a blocker in the graph rather than a sentence in the research**, because this is exactly
  the ordering that gets skipped when the interesting work is next to it.
- **Rejected: folding 0.3.0 into the redesign's release.** It ties a publication that is ready today
  to one that is months away, and it makes the first release of the new model carry two sets of
  changes for anybody reading the notes.
- **Does not cover:** what the consumers do with it. They are ours and they are being fixed against
  0.3.0 already (youndie/konekt#48, youndie/shashki#13); neither has to be on it before this closes.

- AC: `io.github.youndie.petich` 0.3.0 is resolvable from Central, `tools/native-consumer-probe.py`
  says RESOLVED against it, and the tag names the tree the bundle was built from.
- Anchors: `gradle.properties`, `tools/native-consumer-probe.py`, `README.md`

## Dropped 2026-09-19 — publishing would pin a model that is about to be deleted

The owner refused it, and the refusal is better than the item's own argument.

**A version on Central cannot be rewritten or taken back** — this repository's own release decision,
written when 0.2.0 went out. Publishing 0.3.0 would put the interceptor model there permanently, one
release before [B-33](B-33-remove-the-interceptor-model.md) removes it: anyone picking it up in the
interval gets an API with a one-release life, and the reason they would find it at all is that we
put it there.

The item argued that the published version should not be the one carrying the defects. That is true
and it is outweighed. 0.2.0 has **no consumers outside this portfolio**, and the two inside it are
ours and resolve snapshots — konekt and shashki are being fixed against `0.3.0.56` and later without
Central being involved at any point.

**What this costs, stated rather than glossed:** the five fixes of stages 6 and 7 reach Central only
with the new model, so for as long as `stage-9-definition` runs, the published petich is one in which
a failed step is never compensated and a refusal keeps what earlier steps did. That is acceptable
only because the audience is a shop window rather than a user.

**What it changes downstream:** the next publication is the redesigned API, so the upgrade notes
built by [B-24](B-24-a-release-that-adds-a-column-names-it-nowhere.md) will describe a jump from
0.2.0 to that, not to 0.3.0. The per-column `since` values stay correct — the columns did arrive in
0.3.0, and `0.3.0.x` snapshots are real and resolvable — but nothing on Central will ever show that
step.

**Kept as a file rather than deleted**, so the same proposal is refused in ten seconds the next time
it comes up: "release what is ready before starting the rewrite" is the obvious move, and it is wrong
here for a reason that is not obvious.

