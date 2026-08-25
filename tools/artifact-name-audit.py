#!/usr/bin/env python3
"""The file a consumer receives must be named with the version it asked for.

Gradle module metadata gives every artifact two strings: `url`, where the file really is, and `name`,
what it is called once it arrives. They come from different places — the url from the publication's
coordinate, the name from the archive task, which takes it from the PROJECT version — so they can
disagree, and nothing in a build notices when they do. Everything is green: the artifact uploads, the
consumer's resolve succeeds, the file downloads from the right url and lands under a name carrying a
version that was never released. Two releases then put identically-named files on a classpath, and
anything reading file names rather than coordinates — an SBOM, a licence scan, shadow-jar
deduplication, a cache — cannot tell them apart.

Run against a local publication:

    ./gradlew publishToMavenLocal -PVERSION=<v>
    python3 tools/artifact-name-audit.py <v>
"""
import glob, json, os, sys

VERSION = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: artifact-name-audit.py <version>")
M2 = os.path.expanduser("~/.m2/repository/io/github/youndie")

modules = sorted(glob.glob(f"{M2}/*/{VERSION}/*.module"))
if not modules:
    sys.exit(f"no module metadata published under {VERSION} — the audit would pass by finding nothing")

wrong, checked = [], 0
for path in modules:
    artifact = os.path.basename(os.path.dirname(os.path.dirname(path)))
    seen = set()
    for variant in json.load(open(path)).get("variants", []):
        for file in variant.get("files", []):
            entry = (file["name"], file["url"])
            if entry in seen:
                continue
            seen.add(entry)
            checked += 1
            # Only the version is compared, not the whole name. The Kotlin plugin gives some
            # artifacts a base name of its own — the root metadata jar is published as
            # petich-core-<v>.jar but arrives named petich-core-metadata-<v>.jar, and its sources as
            # petich-core-kotlin-<v>-sources.jar — and that is every KMP library in the ecosystem,
            # kotlinx-coroutines included, not a defect: two releases still produce two
            # distinguishable names. The version is the part that must not lie. Matching whole
            # segments, so that 0.1.0 does not satisfy 0.1.0.4.
            if f"-{VERSION}." not in file["name"] and f"-{VERSION}-" not in file["name"]:
                wrong.append((artifact, file["name"], file["url"]))

for artifact, name, url in wrong:
    print(f"{artifact}: published as {url}, arrives named {name}")

if wrong:
    sys.exit(f"\n{len(wrong)} artifact(s) reach a consumer under a name that is not their version")
print(f"checked {checked} artifacts across {len(modules)} modules: every file arrives named with {VERSION}")
