# The documentation gate. CI runs exactly this target — a local check set that differs from the CI
# one turns "green here, red there" into the normal state of affairs, and then neither is read.
#
# The CODE is not in here, deliberately. `./gradlew build` lives in .github/workflows/build.yaml
# together with the three publication audits, and it takes minutes: a contributor editing a document
# should not need a JDK, and a documentation check should not queue behind a Kotlin/Native toolchain
# download. Two gates, each named, each run by CI by name.

PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation rather than a matter of opinion.
gate:
	$(PY) scripts/backlog_index.py --check
	$(PY) scripts/docs_check.py
	$(PY) scripts/coverage_map.py --check
	# THE README'S MODULE TABLE AGAINST THE BUILD SCRIPTS. Here rather than in build.yaml because it
	# reads two files and needs no JDK — and here rather than nowhere because this exact row already
	# rotted once: `petich-chronik` said "jvm only" through the release that gave it a native
	# variant. A consumer reads the table before the build scripts, so a row that outlived its
	# module is the one thing in this repository that compiling cannot catch.
	$(PY) tools/module-table-audit.py

# Non-blocking, on purpose. bdd_report counts scenarios, and demanding a percentage is meaningless
# while acceptance is by hand. code_anchors goes stale because of a refactor in somebody else's
# repository rather than because of an edit here — half the research anchors point at chronik, kore
# and sborka — and it cannot tell a live path from one quoted as obsolete. Both are read by a person.
report:
	$(PY) scripts/bdd_report.py
	$(PY) scripts/code_anchors.py --repos ..

fix:
	$(PY) scripts/backlog_index.py
	$(PY) scripts/coverage_map.py --fix
