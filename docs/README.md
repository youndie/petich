# docs — petich

petich is a distributed saga engine for Kotlin: a multi-step operation is a chain of interceptors,
and a failure at step N undoes exactly what steps N−1…1 actually did. The documentation is layered
and the links run top to bottom.

```
[ Research — why it is built this way; verified vs hypothesis ]
                         │
[ Feature — what it does + BDD ] ──▶ [ Service / module — what owns what ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified and what is a hypothesis | the artefacts each fact names |
| Feature | `features/` | *what* the system does and why; BDD scenarios | this repository |
| Service | `services/` | modules: what each owns, how it is built, how it is wired in | this repository |

**`research/` and one `services/` document, and that is a state rather than a format.** This tree
was started for one subject — taking the engine to Kotlin/Native — and grows with the items that
need it: `services/petich-conformance.md` arrived with the module it describes (B-07). `features/`
arrives the same way; the engine's behaviour is described in [`README.md`](../README.md) meanwhile,
and that is the file to correct if it disagrees with the code.

There is no `screens/` layer and no `api/` layer. petich is a library: it has no client, and the
routes in `petich-ktor` are something a consumer mounts rather than a service anyone deploys.

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items themselves are one
file each in [`backlog/`](backlog/), cited as
[B-03](backlog/B-03-linux-target-on-the-portable-four.md).

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter **and** ordinary markdown links in the body.
- One document, one entity.
- **Language: English**, everywhere — documents, code, comments, commit messages. The rest of the
  portfolio documents in Russian; this repository is English throughout and stays that way.
  Identifiers, coordinates and file names verbatim as in the code.
- **The primary reader is a coding agent.** Every document carries paths into the code, so the reader
  reaches it in one hop. Do not copy what lives in code — DTO fields, column names, versions — give
  the path. A copy rots; a path does not.
- **A fact carries where it was verified.** A version, a variant, a published artefact: read it out
  of the registry or the source and name the address. Anything not verified says *hypothesis* and
  says where it will be settled.
- **An anchor into an artefact rather than a tree** is written the way a jar URL is —
  `chronik-core-0.1.0.module!/variants` — so a path nothing here holds is not reported as rotten for
  ever.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
make check
```

The same thing separately:

```bash
pip install pyyaml
python3 scripts/backlog_index.py --check
python3 scripts/docs_check.py
python3 scripts/coverage_map.py --check
python3 scripts/bdd_report.py
python3 scripts/code_anchors.py --repos ..
```

`code_anchors.py` resolves the research document's references into sibling repositories — `chronik/`,
`kore/`, `sborka/`, `konekt/`, `shashki/` — so they are only checkable where those checkouts sit next
to this one. That is why anchors run on a schedule and do not block.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with no
file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a person —
the machine guards only the membership.

### Services (2)

- [x] [petich-conformance](services/petich-conformance.md) — the rules a storage implementation has
  to satisfy, as cases that can be run against one; what it deliberately does not promise
- [x] [petich-sqlx4k-postgres](services/petich-sqlx4k-postgres.md) — the store a Kotlin/Native
  service can take: the four contracts over sqlx4k, no driver, no schema, no clock of its own

### Research (2)

- [x] [research-native-port](research/research-native-port.md) — what it costs to take petich to
  Kotlin/Native: nine verified facts with addresses, seven decisions, five risks and two open
  questions
- [x] [research-petich-dsl](research/research-petich-dsl.md) — what the interceptor model asks an
  author to know and what a definition would replace it with: five counted facts, seven decisions,
  three risks and three open questions

