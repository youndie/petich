package io.github.youndie.petich.postgres

/**
 * The platform clock, read in exactly one place in this module.
 *
 * Both stores here take a `PetichClock` and default to this. Before B-10 the call sat inline in two
 * different files, which is how a library ends up with two answers to "where does time come from" —
 * and the sqlx4k store, which has no platform to ask, would have been the third.
 *
 * The lint rule is suppressed here and nowhere else: this IS the wall clock, and the reason it is
 * acceptable is that a consumer who cannot afford it now has a parameter to pass instead. The
 * skew defect itself — several replicas stamping one queue — still wants a server-side column
 * default, which changes DDL this library does not ship (youndie/petich#20).
 */
@Suppress(
    "ktlint:kapkan:wall-clock",
    "The module's single platform clock; both stores take a PetichClock and default to it, see #20",
)
internal fun systemTimeMillis(): Long = System.currentTimeMillis()
