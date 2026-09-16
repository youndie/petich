#!/usr/bin/env python3
"""Can a Kotlin/Native build take petich at all?

Everything else in this repository answers for a JVM consumer: the build compiles, the tests pass,
`artifact-name-audit.py` and `jvm-floor-audit.py` read the published metadata, and the proba job in
`publish-snapshot.yaml` resolves every coordinate from a JVM build. None of them can fail because a
native consumer cannot resolve the library — that failure happens in somebody else's build, with a
message about variants and nothing in it about petich.

So this runs that build. `tools/native-consumer-probe/` is a separate Gradle project with one
`linuxX64` executable that declares petich coordinates from the LOCAL publication, names the
engine's types in its own source, and links. Three outcomes, and the difference between the last two
is the whole point:

  RESOLVED     the consumer compiled and linked — the port works for the modules asked about;
  REFUSED      "No matching variant" — the coordinate exists and carries no variant this target can
               use. This is the defect the port fixes, and before the port it is the CORRECT answer;
  NOT PUBLISHED  "Could not find" — the local publication does not have this version at all. That is
               a broken probe run rather than an answer about targets, and it never counts as
               either of the two above.

Usage, always after a local publication of the version being asked about:

    ./gradlew publishToMavenLocal -PVERSION=<v>
    python3 tools/native-consumer-probe.py <v> --expect refusal      # before the port
    python3 tools/native-consumer-probe.py <v> --expect resolve      # after it

It needs a Linux x64 host with the Kotlin/Native toolchain: linking a linuxX64 executable is the
question being asked, so nothing about this runs usefully on the mac.
"""
import argparse
import os
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROBE = ROOT / "tools" / "native-consumer-probe"
BINARY = PROBE / "build" / "bin" / "linuxX64" / "debugExecutable" / "native-consumer-probe.kexe"

parser = argparse.ArgumentParser()
parser.add_argument("version", help="the petich version to resolve, as published to mavenLocal")
parser.add_argument("--expect", choices=("resolve", "refusal"), default="resolve")
parser.add_argument("--modules", default="petich-core",
                    help="comma-separated module names the consumer declares")
args = parser.parse_args()

# The compiler the library is built with, read from the catalogue rather than written out here. Two
# places to bump is one place to forget, and a probe on a different compiler answers about a
# compiler nobody ships.
catalogue = (ROOT / "gradle" / "libs.versions.toml").read_text()
match = re.search(r'^kotlin\s*=\s*"([^"]+)"', catalogue, re.M)
if not match:
    sys.exit("no `kotlin = \"...\"` in gradle/libs.versions.toml — the probe cannot pick a compiler")
kotlin = match.group(1)

started = time.time()
if BINARY.exists():
    # Otherwise a build that never ran would be reported as a link, on the strength of a file left
    # by the previous run.
    BINARY.unlink()

command = [
    str(ROOT / "gradlew"), "-p", str(PROBE),
    "linkDebugExecutableLinuxX64",
    f"-Pprobe.petichVersion={args.version}",
    f"-Pprobe.kotlinVersion={kotlin}",
    f"-Pprobe.modules={args.modules}",
    "--no-daemon", "--console=plain",
]
print(f"$ {' '.join(command)}\n")
completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
output = completed.stdout + completed.stderr

coordinates = [f"io.github.youndie.petich:{module.strip()}" for module in args.modules.split(",")]
refused = [c for c in coordinates if f"No matching variant of {c}" in output]
missing = [c for c in coordinates if re.search(rf"Could not find {re.escape(c)}:{re.escape(args.version)}", output)]

if missing:
    outcome = "NOT PUBLISHED"
    detail = ", ".join(missing) + f" is not in the local repository at {args.version}"
elif refused:
    outcome = "REFUSED"
    detail = ", ".join(refused) + " has no variant this target can use"
elif completed.returncode == 0 and BINARY.exists() and BINARY.stat().st_mtime >= started:
    outcome = "RESOLVED"
    detail = f"{BINARY.relative_to(ROOT)} linked at {time.strftime('%H:%M:%S', time.localtime(BINARY.stat().st_mtime))}"
else:
    outcome = "UNKNOWN"
    detail = f"gradle exited {completed.returncode} and the binary is {'there' if BINARY.exists() else 'absent'}"

# The decisive lines, so a reader does not have to take the verdict on trust.
for line in output.splitlines():
    if any(marker in line for marker in
           ("No matching variant", "Could not find io.github.youndie.petich",
            "BUILD SUCCESSFUL", "BUILD FAILED", "was found. The consumer was configured")):
        print(line.strip())

print(f"\n{outcome}: {detail}")

expected = {"resolve": "RESOLVED", "refusal": "REFUSED"}[args.expect]
if outcome != expected:
    if outcome == "UNKNOWN":
        print("\n--- the last 40 lines of the build ---")
        print("\n".join(output.splitlines()[-40:]))
    sys.exit(f"expected {expected}, got {outcome}")
print(f"as expected (--expect {args.expect})")
