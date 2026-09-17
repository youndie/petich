#!/usr/bin/env python3
"""The targets column of the README's module table, against what the build scripts declare.

A row of that table is the first thing a consumer reads and the last thing anybody edits. The line
that prompted this file said `petich-chronik` was *jvm only — until chronik publishes a native
variant*, and it stayed there through the release that gave it a native variant: B-11 changed the
build script, nothing changed the table, and the wrong row shipped in the README of 0.2.0.

Two independently maintained facts, compared — the same shape as `consumer-coverage-audit.py`:

  * what a module DECLARES: `jvm()` and `linuxX64()` in its `build.gradle.kts` (a `kotlin("jvm")`
    module declares jvm by being one);
  * what the table SAYS: the third column of the row whose first cell is that module's name.

Only the target names are compared, not the prose beside them: a row may explain WHY it is jvm only,
and that sentence is a person's to write. What it may not do is name a target list the build does
not have.

    python3 tools/module-table-audit.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KNOWN = ("jvm", "linuxX64")


def declared(script: str) -> set[str]:
    """The targets a build script declares, read the way Gradle reads them."""
    targets = set()
    if re.search(r'kotlin\("jvm"\)', script) or re.search(r"^\s*jvm\(\)", script, re.M):
        targets.add("jvm")
    if re.search(r"^\s*linuxX64\(\)", script, re.M):
        targets.add("linuxX64")
    return targets


def stated(cell: str) -> set[str]:
    """The targets a table cell names. Prose around them is the author's business."""
    return {name for name in KNOWN if re.search(rf"\b{name}\b", cell, re.I)}


modules = {
    directory.name: (directory / "build.gradle.kts").read_text()
    for directory in sorted(ROOT.iterdir())
    if (directory / "build.gradle.kts").is_file() and "sborkaPublish" in (directory / "build.gradle.kts").read_text()
}
if not modules:
    sys.exit("no published module found — the audit would pass by having nothing to compare")

readme = (ROOT / "README.md").read_text()
rows = {
    match.group(1): match.group(2)
    for match in re.finditer(r"^\|\s*`([a-z0-9-]+)`\s*\|[^|]*\|([^|]*)\|", readme, re.M)
}

wrong, missing = [], []
for module, script in modules.items():
    if module not in rows:
        missing.append(module)
        continue
    says, has = stated(rows[module]), declared(script)
    if says != has:
        wrong.append((module, sorted(says), sorted(has)))

for module in missing:
    print(f"{module}: published, and the README's module table has no row for it")
for module, says, has in wrong:
    print(f"{module}: the table says {says or 'no target at all'}, the build declares {has}")

if missing or wrong:
    sys.exit(
        f"\n{len(missing) + len(wrong)} row(s) out of step with the build. A consumer reads the "
        f"table before the build scripts, so a row that outlived its module is the one thing here "
        f"that cannot be caught by compiling."
    )
print(f"checked {len(modules)} published modules: every row names the targets its build declares")
