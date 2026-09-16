---
id: B-01
title: "A documentation gate, so the port's decisions cannot rot unseen"
status: done
priority: infra
size: S
stage: stage-0-gate
---

# B-01 — `make check` and a CI job that runs exactly it

This repository had no `docs/` at all: seven modules, four python audits, five build decisions
argued in comments, and nothing an agent could read before touching any of them. The port about to
happen is mostly decisions — which targets, which store, which driver, in what order — and a
decision written in a commit message is invisible to the next reader.

- **The gate lands with the first document, not after the backlog is full.** Documentation without a
  check drifts from the code within a release, and the drift is invisible: every file still looks
  authoritative. `backlog_index.py --check`, `docs_check.py` and `coverage_map.py --check` are
  blocking; `bdd_report.py` and `code_anchors.py` are reports a person reads.
- **Rejected: fold the documentation checks into the existing `build.yaml`.** That workflow builds
  Gradle, publishes locally and runs three python audits — minutes. The documentation gate is
  seconds and has no reason to wait behind a Kotlin/Native toolchain download; a separate
  `check.yaml` also keeps `make check` runnable by a contributor who is editing a document and has
  no JDK set up.
- **Does not cover:** the code. `./gradlew build` stays in `build.yaml`, where it already is.

- AC: `make check` is green on a clean checkout, and a pull request that edits a backlog item
  without regenerating the index goes red in CI with the item's id in the message.
- Anchors: `Makefile`, `scripts/`, `.github/workflows/check.yaml`, `docs/README.md`

## Closed 2026-09-16

Shipped with the backlog it guards: the five scripts from `docs-bootstrap` 0.2.0, a `Makefile` whose
`check` target is what CI runs, and `check.yaml` from the template — including the two decisions in
it that are not the obvious ones (no path filters; `--on-main` only on the default branch, because
`status: draft` is the normal state in a pull request).

`code_anchors.py --repos ..` reports the research document's anchors into sibling repositories
(`kore/`, `chronik/`, `sborka/`, `shashki/`, `konekt/`) as resolvable only when those checkouts sit
next to this one. Locally they do; in CI they do not, which is why the anchors job runs on a
schedule and does not block.
