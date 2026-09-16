---
id: B-13
title: "CI downloads the Kotlin/Native toolchain on every run"
status: wip
priority: infra
size: S
stage: stage-5-release
blocked_by: [B-03]
---

# B-13 — `~/.konan` on the build and publish workflows

`build.yaml` runs `./gradlew build` on `ubuntu-latest` with `setup-kotlin` and no cache beyond
Gradle's own. The first build that declares a native target fetches the Kotlin/Native compiler and
its dependencies into `~/.konan` — hundreds of megabytes — and does it again on the next run, and on
every job of the publish workflow. The publish path pays it twice over, since local publication and
the snapshot upload are separate jobs.

- **Measured while closing [B-03](B-03-linux-target-on-the-portable-four.md), and it corrects this
  item's premise.** The first CI run with native targets (run 35150522949) shows the runner *already
  carrying* the toolchain — `Kotlin/Native bundle directory
  /home/runner/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.10 is not empty. Native bundle files
  will be overwritten` — and `downloadKotlinNativeDistribution` running **four times, once per
  module**, over 21:07:22-21:07:51. The job took 1m51s against 2m26s for the documentation-only run
  before it, so the native half did not make the build slower at all. What is worth fixing is
  therefore narrower than "it downloads the toolchain every run", and how many bytes actually cross
  the network is still unmeasured: read it off a run with `--info`, or from the step's own timing,
  before writing a number down.

- **Cache `~/.konan` keyed by the Kotlin version**, because that is what decides the contents; a key
  on the lockfile or the run number either never hits or never invalidates.
- **`linuxX64Test` runs on the same runner, which is the point.** `ubuntu-latest` is a Linux x64 host,
  so the native tests execute rather than merely compile — no second runner and no cross-compilation
  question. The day an Apple target is added, that stops being true, and the decision to leave them
  out (research D2) is what keeps CI single-runner.
- **Rejected: a separate workflow for the native build.** Two workflows mean two green ticks with
  different opinions about the same commit; the whole point of `build.yaml` is that it builds what
  lands.
- **Does not cover:** build time itself. What is being removed is a download, not compilation.

- AC: the second run of `build.yaml` on an unchanged Kotlin version restores `~/.konan` from the
  cache, and the job log shows no toolchain download; the numbers before and after are quoted in the
  item.
- Anchors: `.github/workflows/build.yaml`, `.github/workflows/publish-snapshot.yaml`,
  `gradle/libs.versions.toml`

