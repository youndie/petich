#!/usr/bin/env python3
"""Every module this build publishes must be read back by the consumer job.

`publish-snapshot.yaml` ends with a job that resolves each coordinate as an outside consumer would —
a real build against the published artefact rather than a look in a build directory before the
upload. That job is the only thing in this repository that answers "does this coordinate work for
somebody else", and the coordinates it checks are TYPED OUT BY HAND.

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
checked = set(re.findall(r"io\.github\.youndie:([a-z0-9-]+):", workflow))
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
