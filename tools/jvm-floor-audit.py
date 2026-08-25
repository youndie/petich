#!/usr/bin/env python3
"""The oldest Java a consumer may be on is a decision, not a side effect of the build machine.

Two things have to hold, and the build notices neither:

* the bytecode must be no newer than the floor. A jar built on a newer JDK resolves, compiles and
  then fails in the consumer's runtime with UnsupportedClassVersionError — a message about a class
  file version, with nothing in it about this library. Nobody building on that JDK can reproduce it.
* the floor must be stated in the metadata as org.gradle.jvm.version. A plain kotlin("jvm") module
  gets that attribute from its toolchain; a Kotlin Multiplatform module publishes its jvm variants
  without it, so Gradle has no grounds to refuse a consumer who is too old, and the failure happens
  later and elsewhere.

Run against a local publication:

    ./gradlew publishToMavenLocal -PVERSION=<v>
    python3 tools/jvm-floor-audit.py <v>
"""
import glob, json, os, re, struct, sys, zipfile

FLOOR = int(re.search(r"JVM_FLOOR\s*=\s*(\d+)", open(
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "buildSrc/src/main/kotlin/JvmFloor.kt")
).read()).group(1))
# Java 17 is class file 61, and every release since is one more.
CLASS_FILE = FLOOR + 44

VERSION = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: jvm-floor-audit.py <version>")
M2 = os.path.expanduser("~/.m2/repository/io/github/youndie")

modules = sorted(glob.glob(f"{M2}/*/{VERSION}/*.module"))
if not modules:
    sys.exit(f"no module metadata published under {VERSION} — the audit would pass by finding nothing")

too_new, undeclared, jars_read, variants_read = [], [], 0, 0

for path in modules:
    artifact = os.path.basename(os.path.dirname(os.path.dirname(path)))
    directory = os.path.dirname(path)

    for jar in sorted(glob.glob(f"{directory}/*.jar")):
        if jar.endswith("-sources.jar") or jar.endswith("-javadoc.jar"):
            continue
        with zipfile.ZipFile(jar) as archive:
            classes = [name for name in archive.namelist() if name.endswith(".class")]
            if not classes:
                continue
            jars_read += 1
            # Every class, not the first one: a module can mix output from more than one compilation,
            # and the one that is too new is not likely to be the alphabetically first.
            worst = max(struct.unpack(">H", archive.read(name)[6:8])[0] for name in classes)
        if worst > CLASS_FILE:
            too_new.append((os.path.basename(jar), worst))

    for variant in json.load(open(path)).get("variants", []):
        attributes = variant.get("attributes", {})
        if attributes.get("org.gradle.category") != "library":
            continue
        # Kept from the repository this came from, where Android variants had to be skipped: they
        # carry java-api/java-runtime usage like a jvm one and no org.gradle.jvm.version at all,
        # since what bounds an Android consumer is minSdk. petich publishes no Android variants
        # today, so this filter currently excludes nothing — left in place because adding a target
        # is cheaper than rediscovering why the audit went red when one appears.
        if attributes.get("org.jetbrains.kotlin.platform.type") not in (None, "jvm"):
            continue
        if not any(file["url"].endswith(".jar") for file in variant.get("files", [])):
            continue
        variants_read += 1
        if attributes.get("org.gradle.jvm.version") != FLOOR:
            undeclared.append((artifact, variant["name"], attributes.get("org.gradle.jvm.version")))

for name, version in too_new:
    print(f"{name}: class file version {version}, which needs Java {version - 44} — the floor is {FLOOR}")
for artifact, variant, declared in undeclared:
    print(f"{artifact}: variant {variant} says org.gradle.jvm.version={declared}, expected {FLOOR}")

# A run that read nothing would print a clean line either way, which is the failure this file exists
# to prevent in the first place.
if not jars_read or not variants_read:
    sys.exit(f"the audit inspected {jars_read} jar(s) and {variants_read} jvm variant(s) — it proved nothing")
if too_new or undeclared:
    sys.exit(f"\n{len(too_new)} jar(s) above the floor, {len(undeclared)} variant(s) not declaring it")
print(f"checked {jars_read} jars and {variants_read} jvm variants: all Java {FLOOR} or older, all saying so")
