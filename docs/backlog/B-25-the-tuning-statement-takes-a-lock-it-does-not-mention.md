---
id: B-25
title: "The fill-factor statement takes an ACCESS EXCLUSIVE lock on the busiest table and does not say so"
status: open
priority: P2
size: XS
stage: stage-8-upgrade
---

# B-25 — `ALTER TABLE … SET (fillfactor = 80)`, unqualified

`PetichTable.tuningStatements()` hands a consumer a statement to run beside their generated DDL. Its
KDoc explains what a fill factor buys and why Exposed cannot express it, and says nothing about what
the statement does while it runs: it takes an `ACCESS EXCLUSIVE` lock on the saga table. A statement
waiting for that lock queues every later reader behind it, and on the hottest table in the system
that is a stall, not a migration.

**A consumer's guard found this, not ours.** In the `0.3.0.56` rehearsal, konekt's
`ExpandAndContractTest` — "every migration bounds how long it will wait for a lock" — refused the
trial migration carrying this statement, and accepted it once `SET lock_timeout` was added. A
consumer without that guard gets the stall instead of the refusal.

- **The KDoc names the lock and the consequence**, in the sentence where the statement is handed
  over rather than further down: whoever copies the line is the person who needs to know.
- **And `tuningStatements()` takes an optional timeout**, emitting `SET lock_timeout = …` ahead of
  the `ALTER` when asked. Off by default, because `SET` is session-scoped and a migration tool that
  runs several scripts in one session would carry it into the next one — that is the consumer's
  call, and the parameter is how they make it.
- **Rejected: emitting the timeout unconditionally.** Same reason, from the other side: a library
  that silently changes a session setting is worse than one that says nothing.
- **Does not cover:** the same question for the three `ADD COLUMN` statements of
  [B-24](B-24-a-release-that-adds-a-column-names-it-nowhere.md). On PostgreSQL 11 and later a column
  with a default is not a rewrite, so the lock is brief — brief is not free, and whatever B-24 writes
  should carry the same sentence.

- AC: the KDoc of `tuningStatements()` names the lock; the function can emit a bounded wait when
  asked; a consumer reading only the returned strings can still tell what they cost.
- Anchors: `petich-postgres/src/main/kotlin/PetichTable.kt`, `README.md`
