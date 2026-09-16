---
id: B-15
title: "The first release carrying native variants, and the order it has to go out in"
status: open
priority: P2
size: S
stage: stage-5-release
blocked_by: [B-12]
---

# B-15 — chronik first, then petich, and what the version says

Two facts fix the order. `petich-chronik` cannot carry a native target until `chronik-core` publishes
one ([B-11](B-11-chronik-bridge-blocked.md)), and petich's own release publishes seven coordinates in
one go — so a petich release before chronik's ships six modules with native variants and one without,
which is a state a consumer has to be told about rather than discover.

- **State the order in the release notes rather than in someone's memory.** chronik publishes, petich
  publishes; the same order the `petich-chronik` module forced when it was added.
- **A minor version, not a patch.** New variants in the metadata change what resolves for whom;
  0.1.0 → 0.2.0 says that, and a patch bump would hide a change in the shape of the publication
  behind a number that promises none.
- **The release is accepted by resolving, not by uploading.** The probe from
  [B-02](B-02-native-consumer-probe.md), pointed at the real repository rather than `mavenLocal`, is
  what closes this — a publication that failed halfway leaves artefacts behind and still answers some
  requests.
- **Rejected: releasing petich first and adding `petich-chronik`'s target in a patch.** It puts a
  module in a release whose targets differ from its siblings' for no reason a consumer can see, and
  the patch that fixes it is another full seven-coordinate release anyway.
- **Does not cover:** the snapshot line. Snapshots keep publishing per run from `main` as they do
  now.

- AC: every coordinate the publish workflow names resolves for a `linuxX64` consumer except
  `petich-postgres`, which is JVM-only by decision; the release notes name the order and the reason
  `petich-postgres` is absent from that list.
- Anchors: `.github/workflows/publish-snapshot.yaml`, `gradle.properties`, `README.md`

