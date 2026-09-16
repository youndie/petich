---
id: B-14
title: "The README module table says nothing about targets, and after the port the answer differs per module"
status: open
priority: P2
size: XS
stage: stage-5-release
blocked_by: [B-06]
---

# B-14 — One column, because the answer stops being uniform

Today every published module is JVM-only, so a table without a targets column tells no lies
(`README.md`, the modules table). After the port there are three answers in one repository:
`petich-core` and its three independent siblings on jvm + linuxX64, `petich-ktor` with them,
`petich-postgres` JVM-only for good (research D3), and `petich-chronik` waiting on another
repository ([B-11](B-11-chronik-bridge-blocked.md)). A consumer picking modules has to know which of
those they can take before they try.

- **A column in the existing table, not a new section.** The question "can I use this from my
  target" is asked while reading the module list, and an answer three screens away is not read.
- **The `docs/` link belongs in the same edit.** The research document is where the reasons live;
  without a link from the README nobody arrives at it.
- **Rejected: generating the column from the build scripts.** Four modules and a guard that would
  have to parse Gradle to check itself; the audit that matters is the one that resolves the
  coordinate ([B-12](B-12-guards-meet-the-native-variants.md)), not one that re-reads the same file
  the build reads.
- **Does not cover:** installation snippets per target. The coordinates do not change.

- AC: the module table names the targets of each module, `petich-postgres`'s row says JVM-only and
  why in four words, and `README.md` links `docs/` in one line.
- Anchors: `README.md`, `docs/README.md`, `docs/research/research-native-port.md`

