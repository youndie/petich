#!/usr/bin/env python3
"""What the audits need to know about this repository, read rather than spelled.

The group used to be written out in three of them. It is a string that changes about once in a
library's life -- `io.github.youndie` became `io.github.youndie.petich` when the coordinates moved --
and every copy that is not updated fails in the same unhelpful direction: the path it builds matches
nothing, so the audit reports "found nothing" instead of the answer it exists to give. A guard that
cannot read its own question is worse than no guard, because the run is red for a reason nobody
connects to the rename.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _property(name):
    match = re.search(
        rf"^{re.escape(name)}\s*=\s*(.+)$",
        open(os.path.join(ROOT, "gradle.properties")).read(),
        re.M,
    )
    if not match:
        sys.exit(f"{name} is not set in gradle.properties — the audit has nothing to read")
    return match.group(1).strip()


def group():
    """The Maven group this build publishes under."""
    return _property("sborka.group")


def m2_root():
    """Where `publishToMavenLocal` puts this repository's artifacts."""
    return os.path.expanduser(os.path.join("~/.m2/repository", *group().split(".")))
