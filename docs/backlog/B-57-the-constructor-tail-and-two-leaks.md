---
id: B-57
title: "The engine's constructor grows a tail, and a reason string reaches the outbox"
status: open
priority: P2
size: S
stage: stage-11-review
blocked_by: []
---

# B-57 — three small ones, and the first has a deadline

**The constructor's tail is a binary-compatibility problem with a date on it.** `PetichEngine` takes
`metrics`, `compensationFailureHandler`, `globals`, `announcementFailureHandler` — each added last and
defaulted so existing positional calls still compile. `PetichEngineConfig` exists and is where the
next one belongs. Deciding this **before Central** matters: after it, every new parameter is a
breaking change to a published signature.

**`requireAnnouncementFailureHandler` is missing**, and its two neighbours have it.
`requireOutbox` and `requireSideEffects` refuse at construction an engine whose repository cannot
store what an application clearly means to send. An announcement failure that nobody is listening for
is the same silent drop and has no such switch.

**A reason string reaches the outbox.** `AnnouncementFailureHandler.failed` is handed
`e.message ?: e::class.simpleName`, and whatever it puts in the event goes out to a relay. Mail
failures carry the recipient's address in that message. petich cannot know what is sensitive, but it
can say so where the parameter is declared, and it can stop handing over a raw message by default.

## Acceptance

- The handler tail is decided: either it moves into the config, or the reason for keeping it is
  recorded — before anything is published to Central, because the cost of the decision changes then.
- `requireAnnouncementFailureHandler` exists, or the asymmetry with its two neighbours is explained
  where they are declared.
- The KDoc of `failed` says the `reason` is an exception's message and may carry whatever the far side
  put in it, so an implementation choosing to forward it is choosing knowingly.
