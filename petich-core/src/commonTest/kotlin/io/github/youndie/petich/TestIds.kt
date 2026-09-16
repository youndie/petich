package io.github.youndie.petich

// Unique ids for the fake services in the scenario suites.
//
// `java.util.UUID` is what these used, and it is the only reason two of the suites could not run on
// the second target — the ids are labels a fake hands back, never parsed and never compared to
// anything but themselves. A counter does the same job and does one thing better: a failing run can
// be read, because the id in the message is the same on every run.
private var issued = 0

internal fun testId(prefix: String): String = "$prefix-${(++issued).toString().padStart(8, '0')}"
