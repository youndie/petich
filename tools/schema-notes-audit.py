#!/usr/bin/env python3
"""
The saga table's columns, as three sources describe them, compared.

    python3 tools/schema-notes-audit.py

WHY THIS EXISTS. petich ships no DDL, by a decision that holds: a library that owns the schema's
lifecycle owns its consumer's deploy. What that decision costs is a paragraph — the release has to
say which statements a consumer must write — and a paragraph beside a growing set of columns rots on
the first release nobody remembers to edit. It rotted here before anyone wrote it: 0.3.0 added three
columns and said so nowhere, and the rehearsal against both real consumers failed thirteen saga
tests on `column petiches.compensation_attempts does not exist`, in a message that names neither
petich nor a version (B-24).

WHAT IT COMPARES. Three descriptions of one table, which must agree as SETS:

  * `PetichTable` — the Exposed table a schema generator reads;
  * `petichPostgresSchema()` — the SQL the native module hands the application;
  * the upgrade table in README.md — what a consumer is told to run.

Two of those already had to agree for a saga written by one store to be read by the other; the third
is the one nobody would notice. A three-way comparison also catches the cheaper mistake of adding a
column to one store and forgetting the other, which no test can see until both run against one
database.

AND IT COMPARES THE DECLARATIONS, not only the names. A README that names every column and gets one
of their types wrong passes a set comparison and hands a consumer an ALTER that builds a column too
narrow for what the store writes — a failure that surfaces as truncated data rather than as an
error. The native schema spells each column in SQL and so does the README, so the two texts are
comparable directly.

WHAT IT DOES NOT CHECK: the `since` column. Which release a column arrived in is history, and this
script has no access to history that would not be a guess.

AND WHAT IT COULD NOT SEE UNTIL B-34. `PetichTable` spells its types in Kotlin, so a three-way
comparison of SQL text can only compare the two sources written in SQL — the native schema and the
README — and from the Exposed table it took the column NAME and nothing else. The two stores had
been disagreeing in that blind spot since 0.1.0: every JSON-shaped column is `json()` on the Exposed
side and `TEXT` on the native one. A consumer found the tail of it (konekt's schema guard reported
the DEFAULT and never mentioned the type, because Exposed's migration statements compare defaults
and not types) and the README, which prints one line per column, was handing the native spelling to
both.

What closes it is below as a RULE rather than a list of exemptions: a column `PetichTable` declares
with `json(` must be `TEXT` in the native schema. Stated that way, a new JSON column spelled `JSON`
natively fails and a new one spelled `TEXT` passes, which is the actual agreement between the two
stores. Whether that agreement is safe is not this script's question and cannot be — it is
`NativeSchemaCompatibilityTest`, which runs the Exposed store's whole conformance corpus against a
database built by `petichPostgresSchema()`.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TABLE = os.path.join(ROOT, "petich-postgres/src/main/kotlin/PetichTable.kt")
SCHEMA = os.path.join(
    ROOT,
    "petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt",
)
README = os.path.join(ROOT, "README.md")

# A column declaration and nothing else. The looser "anything in quotes" would also match the index
# name declared in the same file, which is how a guard starts reporting a column that is not one.
#
# `Column<.+?>` and not `Column<[^>]+>`: the second cannot see a column whose type has a generic of
# its own, and the first column with one — `Column<Map<String, PetichStepRecord>>` — was reported as
# missing from a file that declares it. A guard that goes partially blind names the wrong subject,
# which is worse than one that fails: the obvious repair is to edit the document it accuses.
EXPOSED_COLUMN = re.compile(
    r"^\s+public val \w+: Column<.+?>\s*=\s*(\w+)[^(\n]*\(\"([a-z_]+)\"", re.M
)

# The body of the CREATE TABLE for the sagas, up to its closing paren.
NATIVE_TABLE = re.compile(r"CREATE TABLE IF NOT EXISTS \$petiches \((.*?)\n        \)", re.S)
NATIVE_COLUMN = re.compile(r"^\s{12}([a-z_]+) ", re.M)

# The upgrade table's first cell, which may hold several columns as inline code.
NOTES_ROW = re.compile(r"^\|\s*((?:`[a-z_]+`(?:,\s*)?)+)\s*\|", re.M)
NOTES_COLUMN = re.compile(r"`([a-z_]+)`")

# `ADD COLUMN IF NOT EXISTS <name> <everything up to the semicolon>` inside the notes.
NOTES_ADD = re.compile(r"ADD COLUMN IF NOT EXISTS ([a-z_]+) ([^;`]+);")


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def main():
    declared = EXPOSED_COLUMN.findall(read(TABLE))
    exposed = set(name for _, name in declared)
    # The Exposed builder each column was declared with, which is as close to a type as Kotlin
    # source gets without compiling it.
    exposed_builders = {name: builder for builder, name in declared}

    body = NATIVE_TABLE.search(read(SCHEMA))
    if not body:
        sys.exit("schema-notes-audit: the sagas CREATE TABLE was not found in Schema.kt")
    native = set(NATIVE_COLUMN.findall(body.group(1)))

    notes = set()
    for row in NOTES_ROW.findall(read(README)):
        notes.update(NOTES_COLUMN.findall(row))

    if not exposed or not native or not notes:
        sys.exit(
            "schema-notes-audit: one of the three descriptions came back empty "
            "(exposed={0}, native={1}, notes={2}) - the parser lost its subject rather than "
            "finding them equal".format(len(exposed), len(native), len(notes))
        )

    problems = []
    for left, right, ln, rn in (
        (exposed, native, "PetichTable", "petichPostgresSchema"),
        (exposed, notes, "PetichTable", "the README upgrade table"),
    ):
        for column in sorted(left - right):
            problems.append("  {0} has `{1}`, {2} does not".format(ln, column, rn))
        for column in sorted(right - left):
            problems.append("  {0} has `{1}`, {2} does not".format(rn, column, ln))

    # The declarations, for every column the notes hand over as an ALTER. A column the notes
    # describe as part of the original table has no statement to compare, and the sagas written
    # before it existed are the evidence that it was there.
    native_declarations = {}
    for line in body.group(1).splitlines():
        match = re.match(r"^\s{12}([a-z_]+) (.+?),?$", line)
        if match:
            native_declarations[match.group(1)] = " ".join(match.group(2).split())

    for column, declaration in NOTES_ADD.findall(read(README)):
        stated = " ".join(declaration.split())
        declared = native_declarations.get(column)
        if declared is None:
            problems.append(
                "  the README tells a consumer to add `{0}`, which the native schema does not "
                "declare".format(column)
            )
        elif stated != declared:
            problems.append(
                "  `{0}` is `{1}` in the native schema and `{2}` in the README upgrade table: a "
                "consumer following the README builds a different column".format(
                    column, declared, stated
                )
            )

    # THE RULE THE TWO STORES ACTUALLY KEEP, checked rather than assumed. Exposed's `json()` creates
    # a `json` column; the native schema spells every one of them `TEXT`. That disagreement is
    # deliberate and survivable — `NativeSchemaCompatibilityTest` runs the Exposed store's whole
    # corpus against a database the native DDL built — but it is only survivable while it stays THIS
    # disagreement. A new JSON column spelled `JSON` in the native schema would be a third spelling
    # nobody has run, and this is what says so.
    for column, builder in sorted(exposed_builders.items()):
        if builder != "json":
            continue
        declaration = native_declarations.get(column)
        if declaration is None:
            continue
        if not declaration.upper().startswith("TEXT"):
            problems.append(
                "  `{0}` is `json()` in PetichTable and `{1}` in the native schema: the two stores "
                "agree that an Exposed json() column is a native TEXT one, and this is a third "
                "spelling. If it is deliberate, NativeSchemaCompatibilityTest is where it becomes "
                "true rather than hoped.".format(column, declaration)
            )

    if problems:
        sys.exit(
            "the saga table is described three ways and they disagree:\n"
            + "\n".join(problems)
            + "\n\nWhat a consumer runs on an existing schema is README.md's upgrade table and "
            "nothing else, so a column the table does not name, or names differently, is one they "
            "find out about from a saga that fails."
        )

    print(
        "the saga table's {0} columns agree across PetichTable, the native schema and the "
        "upgrade notes".format(len(exposed))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
