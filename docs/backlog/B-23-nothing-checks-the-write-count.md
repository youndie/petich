---
id: B-23
title: "The write count the README sells the engine on is measured by nothing"
status: done
priority: P3
size: S
stage: stage-7-write-cost
---

# B-23 — "about 17 database writes, 11 of them into the saga table"

The Cost section names a number taken once, by hand, through `pg_stat_user_tables`, for a scenario
it describes in half a sentence (`1 INSERT + one UPDATE per interceptor + 1 final, plus the
suspend/resume machinery`). Nothing reproduces it. Four items have since changed what the engine
writes — a rollback that records its attempts, a stamp on every row, a fingerprint on every write —
and the number stayed the same because none of them added a write, which is a fact nobody can
check without redoing the measurement by hand.

- **A count of repository calls is the honest guard**, not `pg_stat_user_tables`: the statistics
  collector is asynchronous and cumulative, so a test reading it is a test that fails on a busy
  machine. What the README is really claiming is the number of statements the engine issues, and a
  counting repository observes exactly that, deterministically, with no database at all.
- **The scenario has to be written down before it can be counted.** The number depends on how many
  interceptors, how many suspensions and how many phases are involved, and the sentence in the
  README names only part of that. Whatever the test fixes is what the README should then describe.
- **Rejected: deleting the number.** It is the most useful paragraph in that file for anyone
  deciding whether to adopt the engine — the point is to make it checkable, not to stop saying it.
- **Does not cover:** the cost of each write, which is [B-22](B-22-eleven-updates-rewrite-a-column-that-never-changes.md)
  and is done. This is about how many there are.

- AC: a test names a scenario, counts the writes the engine issues for it, and fails when that count
  changes; the README's sentence describes the same scenario as the test, in the same terms.
- Anchors: `README.md`, `petich-core/src/commonTest/kotlin/`,
  `petich-core/src/commonMain/kotlin/Petich.kt`

## Closed 2026-09-19

`WriteCountTest` names a scenario — six steps across the five phases, three of them emitting one
outbox event — and counts what the engine asks of storage for it. **A repository that counts its own
calls, not `pg_stat_user_tables`:** the collector is asynchronous and cumulative, so a test reading
it fails on a busy machine, and what the README claims is really the number of statements the engine
issues.

**Eight writes to the saga table:** one `INSERT`, one `UPDATE` per step, one that completes it.
Events ride along inside those writes and add none.

**And the old sentence was wrong about the thing it emphasised.** "Plus the suspend/resume
machinery" implies a suspension costs extra writes. It does not — it **moves** one: the suspending
step writes `PENDING_SIGNATURE` instead of the `Proceed` it never makes, and is deliberately not
re-executed on resume, so the same six steps cost the same eight writes either way. The test was
written asserting two extra writes and the run refused it; the assertion now states what the engine
does, with the reason beside it.

**The figure that was there could not be reproduced at all.** "About 17 writes, 11 of them into the
saga table" was taken once, by hand, for a scenario nobody wrote down, and no shape of this saga
reaches it. It is retired rather than confirmed — the README now describes the scenario the test
fixes, in the same terms, which is what this item asked for.

**One honest gap, stated in the README rather than hidden:** a resume issues a `saveOrGet` that
changes no row here, so this counter does not see it, while in the sqlx4k store it is an
`INSERT … ON CONFLICT DO NOTHING`. A count of statements is one higher than this count of row
changes, and the README says which of the two it means.

**Proved by mutation:** an extra `updatePetich` per step fails both cases. It also fired B-21's chain
guard — the injected write carried a stale fingerprint — which is two independent checks reacting to
one defect, and the second of them was not written for this.

**Where it ran:** `./gradlew build` on the Linux box; the two cases pass on `jvm` and `linuxX64`.

