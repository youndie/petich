---
id: B-12
title: "After the port, half of what is published is checked by nobody"
status: done
priority: P1
size: S
stage: stage-5-release
blocked_by: [B-03]
---

# B-12 — The audits and the consumer job against native variants

Three python audits guard this repository's publications, and a fourth check — the `proba` consumer
job in sborka's `publish-wip.yaml` — resolves each coordinate as an outside consumer. Against native
variants they line up like this
([research §1.8](../research/research-native-port.md)):

* `artifact-name-audit.py` walks every file of every variant, so klibs come under it automatically;
* `jvm-floor-audit.py` skips non-jvm variants by design — correct, and it means it says nothing
  about them;
* `consumer-coverage-audit.py` compares published *modules* against the coordinate list, and both
  sides count modules rather than variants, so it stays green while the native half goes unresolved;
* the `proba` consumer build is a JVM build, pinned to Java 21.

So the moment the targets land, every guard here either ignores the new variants by design or asks a
question only a JVM consumer can answer, and nothing about that is red.

- **Either the consumer job gains a native consumer, or the manual probe is named as the only thing
  standing there.** Both are acceptable; pretending the existing guards cover it is not. chronik
  wrote the same gap down as "the native half of the metadata stays hand-checked once" — inheriting
  it silently is what this item refuses.
- **Rejected: extending `jvm-floor-audit.py` to native variants.** There is no floor to check there;
  a klib has no class file version and no `org.gradle.jvm.version`. Making it "cover" them would
  give a check that passes by finding nothing — the failure that file's own vacuity guard exists to
  prevent.
- **Does not cover:** running a native consumer in the publish workflow if that needs a Kotlin/Native
  toolchain in a job that today needs only a JDK. If it does, the cost is named in the item and the
  probe from [B-02](B-02-native-consumer-probe.md) stays the acceptance.

- AC: a deliberately broken publication — a native variant removed from one module — is reported by
  something that runs in CI, or the release checklist names the probe as the manual step and says
  which modules it covers.
- Anchors: `tools/jvm-floor-audit.py`, `tools/artifact-name-audit.py`,
  `tools/consumer-coverage-audit.py`, `.github/workflows/publish-snapshot.yaml`,
  `.github/workflows/build.yaml`

## Closed 2026-09-16

`build.yaml` runs the native consumer against the publication it has just made, on every push and
pull request:

```yaml
- name: A Kotlin/Native consumer can resolve what was published
  run: python3 tools/native-consumer-probe.py ci-${{ github.run_number }} --expect resolve
```

**It answers two questions, and the cheaper one is the one no build can.** Before compiling
anything the probe checks that every module *declaring* a native target has actually *published*
one — because a missing `<module>-linuxx64` directory is the same failure a consumer meets as *no
matching variant*, and a build that compiled the target perfectly well says nothing about it. Then
it resolves, compiles and links a `linuxX64` binary against the publication.

**The module list is derived, not typed.** Both facts come from the build scripts — the publish
convention is applied and a native target is declared — for the reason
`consumer-coverage-audit.py` exists one level up: a list written into a workflow is a list nobody
updates when the eighth module arrives. Today it derives `petich-conformance`, `petich-core`,
`petich-idempotency`, `petich-ktor`, `petich-outbox-core`, `petich-scheduler`, and refuses to run at
all if it derives nothing.

**The control, run rather than reasoned about.** One published variant deleted from the local
repository:

```
$ rm -rf ~/.m2/.../petich-scheduler-linuxx64/b12-probe
$ python3 tools/native-consumer-probe.py b12-probe --expect resolve
declared native modules: petich-conformance,petich-core,petich-idempotency,petich-ktor,…
declared a native target and published no native variant at b12-probe: petich-scheduler
```

Named, by module, before a consumer is built.

**What is still only answered by hand, and it is now written down rather than assumed:** the
release itself. The probe in CI reads a *local* publication; nothing here resolves the coordinates
from the repository they were uploaded to for a native consumer — proba does exactly that for the
jvm half and cannot for this one. [B-15](B-15-release-order-and-the-first-native-version.md) carries
that as the release step, with the probe pointed at the real repository.
