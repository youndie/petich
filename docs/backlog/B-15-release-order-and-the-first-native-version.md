---
id: B-15
title: "The first release carrying native variants, and the order it has to go out in"
status: done
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

## Rehearsal — 2026-09-17, on 0.2.0.39

A version on Central can never be rewritten or taken back, so the release was rehearsed on a
snapshot first: the same tree, the same publication path, a number nobody has to live with.
`0.2.0.39` went to reposilite from `main` by the ordinary workflow.

| What was asked | Answer |
|---|---|
| the JVM consumer job (`proba`) resolving all nine coordinates from a real remote | success |
| the native probe against that remote — `--repository https://reposilite.kotlin.website/snapshots` | RESOLVED, `.kexe` linked |
| **shashki**, a real consumer: `:server:build` and `:server:test` against the candidate | green, no code change |
| **konekt**, a real consumer of six petich modules including `petich-ktor`: four modules built, `:server:test` and `:feature:purchase-server-domain:test` | green, no code change |
| a saga end to end on artefacts **fetched from the repository** rather than built locally | `PENDING_SIGNATURE → COMPLETED`, its event in the outbox |
| konekt's **whole** build — the way its own gate runs it: every module, the conformance tests against Testcontainers, the Android application, the Apple targets compiled | BUILD SUCCESSFUL |

**The whole-build line needed checking before it could be written**, and that is worth more than the
line. `./gradlew build` came back green in 30 seconds with *1025 actionable tasks: 26 executed, 999
up-to-date* — a number that proves the build ran, not that anything was rebuilt against the new
version. Reading the test-result files by date showed three modules exercised today and one,
`shared/server-common`, carrying results from five days earlier. Forced with `--rerun-tasks` it is
19 tests, 0 failures, against the candidate. A green incremental build is a claim about Gradle's
inputs, not about the dependency that changed.

**Why the two consumers are the part worth having.** `proba` builds a synthetic consumer, which
answers "does this coordinate resolve"; shashki and konekt answer the question that matters to the
jvm half of the world — *does the code that already depends on petich still compile and pass its
own tests*. konekt is the one that could have gone wrong: it takes `petich-ktor`, whose Ktor
coordinates changed from `-jvm` to the platform-agnostic ones in [B-06](B-06-ktor-module-and-the-jvm-pinned-catalogue.md).
Both catalogues were edited locally and restored; neither repository carries a commit from this.

**What the rehearsal still does not prove:** that Central serves it. An upload that succeeded and a
coordinate a stranger can resolve are two different events, and the second one is the acceptance
below — run against `https://repo1.maven.org/maven2` once the bundle is released.

## Closed 2026-09-17 — petich 0.2.0 is on Central

The order held: chronik **0.2.0** first ([B-11](B-11-chronik-bridge-blocked.md)), petich **0.2.0**
after it, so no release ever shipped six modules with native variants and a seventh without.

**The acceptance is a consumer resolving from the repository a stranger would use**, not an upload
that returned success:

```
$ python3 tools/native-consumer-probe.py 0.2.0 --repository https://repo1.maven.org/maven2 --expect resolve
-Pprobe.modules=petich-chronik,petich-conformance,petich-core,petich-idempotency,petich-ktor,
                petich-outbox-core,petich-scheduler,petich-sqlx4k-postgres
RESOLVED: … native-consumer-probe.kexe linked
```

Eight modules, every one declaring a native target, all resolved from Central. Then the same binary
against a real Postgres, on the **released** artefacts rather than a local publication:

```
pass 1: result=ActionRequired stored=PENDING_SIGNATURE version=1
pass 2: result=Success  stored=COMPLETED version=3
outbox: [(shipped-order-1, order.shipped)]
SAGA OK: created, suspended, resumed, completed, and its event is in the outbox
```

**`petich-postgres` is absent from that list by decision, and Central shows exactly that:**

```
petich-postgres-0.2.0.jar        200
petich-postgres-linuxx64 …klib   404
```

It is Exposed over JDBC, and JDBC is a JVM interface rather than a protocol (research D3). A
Kotlin/Native service takes `petich-sqlx4k-postgres` instead, which speaks the same columns — so a
system can move one process at a time with both stores pointed at one database.

**What the release rehearsal bought, said once because it is the reusable part.** Everything above
had already been run on `0.2.0.39` in reposilite: the same probe against a real remote, both real
consumers built and tested, the saga on fetched artefacts. A version on Central cannot be rewritten
or taken back; a snapshot costs nothing and answers the same questions. The only thing the rehearsal
could not answer is whether Central serves it, which is what the run above is for.

**The last step was a person's, by design.** `central.yaml` in youndie/sborka uploads the bundle
staged and stops; the owner pressed Publish in the portal, for both repositories. Nothing here
automates that, and this item does not propose to.
