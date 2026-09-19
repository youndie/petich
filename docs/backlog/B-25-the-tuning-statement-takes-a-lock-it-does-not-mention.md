---
id: B-25
title: "The fill-factor statement takes an ACCESS EXCLUSIVE lock on the busiest table and does not say so"
status: done
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

## Closed 2026-09-19

The KDoc names the lock in the paragraph that hands the statement over, not further down — whoever
copies the line is the person who needs to know — and `tuningStatements(lockTimeout = …)` returns the
bound ahead of the `ALTER`.

**A parameter and not a default**, for the reason the item gave and which survived writing it: `SET`
is session-scoped, so a migration tool running several scripts in one session would carry the bound
into the next one. A library that quietly changes a session setting is worse than one that says
nothing; whether to bound the wait, and at what, is the application's decision, and the parameter is
how it says so.

**Exercised against a real database rather than asserted as a string.** A `SET` Postgres refused
would still look correct in a unit test comparing text, so the test runs both statements and reads
the fill factor back from `pg_class`. Two mutations after the change was committed: emitting the
bound *after* the `ALTER` fails the test — where it bounds nothing — and dropping it entirely fails
the same one.

**The README half arrived with [B-24](B-24-a-release-that-adds-a-column-names-it-nowhere.md)**, whose
upgrade notes already point at this paragraph and say the `ADD COLUMN`s belong under the same bound
if a consumer's migrations require one. What this adds there is the sentence about *waiting* for the
lock, which is the part that costs: the statement itself is brief, and a statement queued behind
somebody else's transaction is not.

**Where it found itself.** konekt's `ExpandAndContractTest` — "every migration bounds how long it
will wait for a lock" — refused the trial migration carrying this statement during the `0.3.0.56`
rehearsal, and accepted it once the bound was added. A consumer without that check gets the stall
instead of the refusal, which is the whole argument for saying it here.

