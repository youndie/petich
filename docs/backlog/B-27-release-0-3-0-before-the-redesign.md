---
id: B-27
title: "0.3.0 goes to Central before the redesign starts"
status: open
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
