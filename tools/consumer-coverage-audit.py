#!/usr/bin/env python3
"""Every module this build publishes must be read back by the consumer job.

`publish-snapshot.yaml` hands a list of coordinates to sborka's `publish-wip` workflow, which
resolves each one as an outside consumer would — a real build against the published artefact rather
than a look in a build directory before the upload. That job is the only thing that answers "does
this coordinate work for somebody else", and the coordinates it checks are TYPED OUT BY HAND.

So a new module publishes and is checked by nobody, and nothing about that is red: the upload
succeeds, the consumer job passes on the six it was told about, and the seventh is simply absent
from the question. `petich-chronik` arrived in #18 and spent its first release in exactly that
position.

The two lists come from different places and cannot drift silently any more: the modules that apply
the publish convention, and the coordinates the workflow names.

    python3 tools/consumer-coverage-audit.py
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# What the build publishes: a module directory whose build script applies the publish convention.
# Read from the build scripts rather than from a list, because a list is the thing being checked.
published = {
    d.name
    for d in ROOT.iterdir()
    if (d / "build.gradle.kts").is_file() and "sborkaPublish" in (d / "build.gradle.kts").read_text()
}
if not published:
    sys.exit("found no publishing modules at all — the audit would pass by finding nothing")

workflow = (ROOT / ".github/workflows/publish-snapshot.yaml").read_text()
# With or without a version on the tail. The coordinates lost theirs when the consumer job moved
# to sborka — the version is appended there, because this file cannot name a version the run has
# not produced yet — and a regex that still required one found nothing at all.
# THE GROUP IS READ, NOT SPELLED. It used to be written out here, which made this file the third
# place naming it — after `gradle.properties` and the workflow — and the one nobody would think to
# change. Moving to `io.github.youndie.petich` left the pattern matching nothing, and a pattern that
# matches nothing is a guard that reports "no coordinates" instead of the answer it exists to give.
group = re.search(r"^sborka\.group=(.+)$", (ROOT / "gradle.properties").read_text(), re.MULTILINE)
if not group:
    sys.exit("gradle.properties names no sborka.group — the coordinates cannot be recognised")
pattern = rf"^\s*{re.escape(group.group(1).strip())}:([a-z0-9-]+)(?::|\s*$)"
checked = set(re.findall(pattern, workflow, re.MULTILINE))
if not checked:
    sys.exit("found no coordinates in the consumer job — the audit would pass by finding nothing")

missing = sorted(published - checked)
extra = sorted(checked - published)

for name in missing:
    print(f"published and not read back by the consumer job: {name}")
for name in extra:
    print(f"named in the consumer job and not published by this build: {name}")

if missing or extra:
    sys.exit(
        f"\n{len(missing) + len(extra)} coordinate(s) out of step. The consumer job is the only "
        "check that asks whether a coordinate works for anybody else, and a module it does not "
        "name is a module nobody asked that about."
    )

print(f"consumer job covers all {len(published)} published modules")
