---
id: B-21
title: "A saga's position is an index into a chain assembled at runtime, and nothing notices when the chain changes"
status: wip
priority: P1
size: S/M
stage: stage-6-recovery
---

# B-21 — `currentInterceptorIndex` survives a deploy that changes what it points at

The chain is built per pass: filter the registered interceptors by `phase` and `supports(payload)`,
sort by priority descending. `currentInterceptorIndex` is a position in *that* list, and the saga
row stores nothing else — no name, no key, no fingerprint. A deploy that adds, removes or
re-prioritises an interceptor in the same or an earlier phase silently re-points every suspended
saga at a different step, and the rollback with it. Equal priorities make it worse without any
deploy: `sortedByDescending` is stable, so ties are resolved by the order the list was registered
in, which is the DI container's business rather than anybody's decision.

- **The cheap measure is a fingerprint of the prefix already executed**, in one nullable column,
  checked on resume: a hash of the ordered names of the steps up to and including the current
  index. Mismatch refuses to run and says so, instead of executing a different step. That turns a
  silent wrong step into a loud stop, which is most of the value for code that moves money.
- **The prefix, not the whole chain, and that is the whole design.** A fingerprint over the entire
  chain refuses every in-flight saga after any legitimate deploy that appends a step. A guard that
  fires on a normal release is switched off in its first week, and then the silent case is back with
  a disabled check in front of it.
- **Ties get refused where the chain is assembled, not at construction.** `supports()` takes a
  payload instance, so two same-phase, same-priority interceptors for disjoint payload types are
  legitimate and a constructor cannot tell them from a real collision. The check belongs in the
  function that filters and sorts — which is the same place the fingerprint is computed, so it is
  one function that builds the chain, refuses a tie and returns the hash.
- **Rejected: a step key persisted instead of an index**, now. It is the right end state and it is a
  schema change, a migration for rows in flight, and a new identity every interceptor has to
  declare. The fingerprint costs one nullable column and no data migration, and it makes the
  failure loud — which is what the step key would also do, only later.
- **Does not cover:** the dump. Printing the resolved chain per payload type at startup, and
  snapshotting it in a test, is the reviewable half and rides along here: it costs a few lines and
  makes the order of compensations something a person can read in a diff instead of reconstructing
  from `supports()` and priorities.

- AC: a saga suspended before a chain change refuses to resume with a message naming the mismatch,
  instead of running a different step; two interceptors with equal priority in one phase that both
  support a payload fail the pass loudly; the resolved chain for a payload type can be printed and
  is snapshotted by a test.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-postgres/src/main/kotlin/PetichTable.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt`
