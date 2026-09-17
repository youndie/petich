---
id: B-11
title: "petich-chronik stays JVM-only until chronik publishes a native variant"
status: done
priority: P2
size: S
stage: stage-4-bridge
---

# B-11 — The bridge module, blocked outside this repository

`petich-chronik` depends on `io.github.youndie.chronik:chronik-core` 0.1.0, and that coordinate is
JVM-only on Central: `chronik-core-linuxx64` 0.1.0 returns 404, and 0.1.0 is the only release
([research §1.6](../research/research-native-port.md)). chronik's own repository already declares
`linuxX64()` and closed it as its B-16 on 2026-09-15 — merged, not released. A module cannot declare
a target its dependency cannot resolve for.

- **The item stays open and blocked rather than being quietly attempted.** The distinction matters
  here more than usual: the blocker is a 404, which is a fact anyone can recheck, not an opinion
  about someone else's schedule.
- **Rejected: vendoring the two chronik types the bridge names.** It duplicates a contract owned in
  another repository to make a build green, which is the failure this module was carved out of the
  engine to avoid.
- **The release order follows from this, not the other way round:** chronik publishes a native
  variant, then petich — [B-15](B-15-release-order-and-the-first-native-version.md).
- **Does not cover:** asking chronik for the release. That is a request in that repository, and
  filing it there is part of closing this one.

- AC: `curl` on `chronik-core-linuxx64` returns 200; then `petich-chronik` declares `linuxX64()`, its
  `commonTest` runs on both targets, and the module's transactional test — which needs a real
  Postgres and Testcontainers — stays where it is, `jvmTest`, with that stated in the build file.
- Anchors: `petich-chronik/build.gradle.kts`,
  `petich-chronik/src/jvmTest/kotlin/OneTransactionTest.kt`, `gradle/libs.versions.toml`

## Iteration 1 — 2026-09-16: still blocked, and the request is filed

Rechecked rather than assumed, because the whole item rests on one HTTP status:

```
$ curl -o /dev/null -w "%{http_code}" .../chronik/chronik-core-linuxx64/0.1.0/chronik-core-linuxx64-0.1.0.klib
404
$ curl -s .../chronik-core/maven-metadata.xml | grep version
<version>0.1.0</version>
```

Unchanged: `0.1.0` is still the only release and it is jvm-only. On chronik's side the work is
merged — `linuxX64()` landed in its #19 and the sqlx4k store in #20 — so what is missing is a
release, not code.

**Asked for, in the repository that can answer:** [youndie/chronik#21](https://github.com/youndie/chronik/issues/21),
with the 404 quoted and the release order spelled out. The item stays `open` rather than becoming a
question: there is nothing for the owner to decide here, only something to publish.

**What is ready to do the moment that returns 200:** one line in
`petich-chronik/build.gradle.kts`, the version bump in the catalogue, and the module's `commonTest`
running on both targets. `OneTransactionTest` stays in `jvmTest` — it needs a real Postgres through
Testcontainers, and that is a JVM stand rather than a petich limitation.

## Closed 2026-09-17

The 404 this item rested on is a 200:

```
$ curl -o /dev/null -w "%{http_code}" .../chronik-core-linuxx64/0.2.0/chronik-core-linuxx64-0.2.0.klib
200
$ curl -s .../chronik-core/maven-metadata.xml | grep version
<version>0.1.0</version>
<version>0.2.0</version>
```

chronik released **0.2.0** carrying its native variants ([youndie/chronik#21](https://github.com/youndie/chronik/issues/21)
→ its B-18). The bundle was uploaded by `central.yaml` in youndie/sborka, which leaves it staged on
purpose, and released by the owner in the portal — the one step of this whole port that no workflow
here performs.

Then the bridge: `linuxX64()` in `petich-chronik`, the catalogue moved from `0.1.0` to `0.2.0`, and
**8 tests on `jvmTest`, 4 on `linuxX64Test`**, 0 failures. `OneTransactionTest` stays in `jvmTest`:
it needs a real Postgres through Testcontainers, which is a property of the stand rather than of
petich, and the module's build file says so.

**The same finding as B-03, in a file nothing had compiled for a second target:** a comma inside a
backticked test name, which Kotlin/Native rejects as an identifier. Renamed. Three such names were
found in the engine when it gained the target and one here — the pattern is that every module meets
it exactly once, at the moment its tests first compile for a second target.

**The snapshot was used and then dropped, deliberately.** While the bundle was staged, the work was
verified against `0.2.0.17` from reposilite — which proved the bridge portable without waiting — and
the catalogue was moved to the release before this merged. A release that depends on a snapshot is
not a release, and the number in the catalogue is what a consumer resolves.
