---
id: B-57
title: "The engine's constructor grows a tail, and a reason string reaches the outbox"
status: done
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

## Findings

**The first half's premise is wrong, and the bytecode says so.** Moving the handler tail into
`PetichEngineConfig` was proposed to stop every later parameter being a breaking change on a
published signature. Both classes take defaulted parameters, so both compile to
`(…, int mask, DefaultConstructorMarker)` — read off `javap` for `PetichEngineConfig.class` and
`PetichEngine.class`. Adding a field to the config changes that descriptor exactly as adding one to
the engine does; the move relocates the problem without touching it. What would buy binary
compatibility is a value the caller builds and `copy`s, which is a different decision with a consumer
migration in it, and which nobody asked for here.

**So the tail stays, with a rule rather than a precedent.** Values — numbers, durations, booleans,
maps — in `PetichEngineConfig`; anything the engine calls into or wraps in a guard, in the
constructor. Two reasons beyond taste: the `require*` flags are assertions **about** the
collaborators, and beside them each becomes a field asserting about its neighbour; and "config" names
a thing you load from a file, while nothing in that class has behaviour. Recorded as D16 in the
research document and beside the last parameter of the tail, which is where the next one will be
decided.

**The four "last, and defaulted" comments were the evidence, not the argument.** Each is true about
its own parameter and a reason for none of them.

**`requireAnnouncementFailureHandler` had to read the constructor parameter, not the property.** The
engine wraps that parameter in `GuardedAnnouncementFailureHandler` in a property of the same name
declared one line above `init`, and the wrapper is never a `NoOpAnnouncementFailureHandler` — so a
check written against the property would be a guard that cannot fire. Its neighbour
`requireCompensationHandler` already reads the parameter and has a test that proves it; this one has
the same test, plus the half that guard usually lacks: **a real handler must get through.** Both
halves were checked by mutation.

**The third half is a KDoc and stays one, deliberately.** The item allows "it can stop handing over a
raw message by default". Cutting the message down would leave the counter, the timeout and nothing to
debug with, and the only party that knows what may leave the system is the application. The message
is handed over whole; what changed is that `failed`'s KDoc now names what is in it — a mail
transport's recipient address, an HTTP client's full URL with its query string — and that whatever
the handler returns goes into the outbox and out through a relay, which is usually not where the
application's logs go. An implementation forwarding it verbatim now chooses to.

**Checked by two mutations.** Pointing the guard at the guarded property → `Expected an exception of
class java.lang.IllegalArgumentException to be thrown, but was completed successfully`; making it
refuse whenever the flag is set → the refusal fires on the engine built with a real handler.

**Verification.** `./gradlew build` on the Linux box, exit code read rather than piped: green,
including `petich-core:linuxX64Test` and the conformance corpus against a real Postgres. `make check`
and `code_anchors.py --repos ..` clean. No consumer migration: the new flag is off by default and
every other change is a comment.

**Found on the way, and for a consumer's backlog.** Nothing in konekt or shashki passes an
`announcementFailureHandler`, so both are in exactly the state the new flag refuses — whether they
want the flag on is theirs to decide, and turning it on is a startup-refusing change.
