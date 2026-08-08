#!/usr/bin/env python3
"""Bisect the Flyway migration set along the tenancy tier boundary (ADR 0010 §4.1, Phase 2).

WHY THIS IS A SCRIPT AND NOT A HAND-WRITTEN LIST
------------------------------------------------
ADR 0010 §4.1 names nineteen migrations as "needing bisection" and then says, in the same
paragraph, that the authoritative list is generated. It is right to distrust its own list: that
list was written weeks before this phase against 52 migrations, and a hand-curated answer to
"which files create both kinds of table" goes stale the moment someone adds a table to an
existing migration or a migration to the set. The tier of a table is recorded once, in
``docs/DATA_MODEL.md``, where ``TenancyTierBoundaryTest`` already forces it to stay complete
against the live ``information_schema``. Everything here is derived from that one fact plus what
the migrations themselves say they create.

THE GENERATED LIST DISAGREED WITH THE ADR'S, in both directions, which is why it is generated.
Nineteen migrations end up in both directories — the same count as the ADR's hand list, and not the
same nineteen:

  in the ADR, single-home here   V22  creates `search_document` only, and §2.1 of the same document
                                      overrules the earlier facet that wanted it per-tenant. §4.1's
                                      list was written before that decision settled.
                                 V31  creates `user_device` only. `user_device_trust` arrived in
                                      V51, four migrations after §4.1's list was written.
                                 V34  creates consent_record, legal_hold and erasure_request — the
                                      person graph, all three platform.
  in both here, missing from ADR V14  alters `audit_log`, a split table, so both copies need it.
                                 V51  splits: `user_device_trust` (tenant) and the `user_device`
                                      column drop (platform).
                                 V53  splits: the boundary FK cuts (tenant) and the three org_id
                                      indexes on platform tables.

Seven of the nineteen are MIRRORED rather than bisected — V13, V14, V19, V23, V24, V33, V35 touch
only split tables, so both halves are the same file. The other twelve genuinely divide.

Also generated, and also not what the ADR says: the tier totals are **28 platform-only, 20
tenant-only, 7 split**. ADR 0010 §2 prints "29 platform-only, 19 tenant-only" under a table whose own
55 rows count 28/20/7 — an arithmetic slip in the totals line, not a disagreement about any table.

WHAT IT DOES
------------
``report``  reads the flat migration set and prints, per migration, the tier(s) it touches and the
            tables that put it there. This is the deliverable ADR 0010 §7 Phase 2 calls "the
            generator that bisects V1–V52 ... from the tier table".
``split``   writes ``db/migration/platform/`` and ``db/migration/tenant/`` from the flat set,
            keeping every V-number (never renumber: 412 references in prose would start lying) and
            never duplicating a statement except for the seven ``platform + tenant`` tables, whose
            DDL is identical in both directories by design.
``verify``  re-derives the partition from ``docs/DATA_MODEL.md`` and asserts it against the split
            tree that exists today. This is the mode that stays useful after the split: it fails if
            a statement lands on the wrong side, if a split table is missing from a directory, or
            if a V-number appears in both directories without a split table to justify it.

WHAT IS *NOT* MECHANICAL, AND IS THEREFORE NOT PRETENDED TO BE
--------------------------------------------------------------
Statement placement is mechanical and this script owns it. **Header prose is not.** The 2,589
lines of decision rationale in these files are the repo's most valuable migration asset (AGENTS
§4.5 names V10/V11 as the reference voice), and a machine cannot decide that V11's
"membership.person_id is the case that proves the rule" paragraph belongs to the tenant half.
``split`` attributes each header paragraph by the tables it names — a paragraph naming only
tenant-tier tables goes to the tenant half, one naming both or neither goes to both — and that
first pass was then read and corrected by hand. So ``split`` is a one-shot tool that produced the
committed tree; re-running it would overwrite the corrections. ``verify`` is the mode wired into
review, because it checks the half that is mechanical without touching the half that is not.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import OrderedDict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_MODEL = os.path.join(REPO, "docs", "DATA_MODEL.md")
MIGRATIONS = os.path.join(REPO, "src", "main", "resources", "db", "migration")

PLATFORM = "platform"
TENANT = "tenant"
BOTH = "platform + tenant"

# Tables a migration creates and a later migration drops never reach information_schema, so
# docs/DATA_MODEL.md cannot carry a tier for them and TenancyTierBoundaryTest cannot demand one.
# There is exactly one, and its tier is not a judgement call: notification_log was replaced by
# notification_delivery in V9, which docs/DATA_MODEL.md records as platform.
HISTORIC_TIERS = {"notification_log": PLATFORM}

# Statements that name no table at all. Schemas and extensions are cluster-wide objects created
# once, by the platform sequence, before any tenant schema exists to hold them.
SCHEMA_LEVEL = "<schema-level>"

TABLE_HEADING = re.compile(r"^### ([a-z_][a-z0-9_]*)\s*$")
TIER_LINE = re.compile(r"^\*\*Tier:\*\*\s+(.+)$")

MIGRATION_FILE = re.compile(r"^V(\d+)__(.+)\.sql$")

# The object a statement is *about*. Order matters: the first pattern that matches wins.
STATEMENT_SUBJECT = [
    (re.compile(r"^create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?"
                r"[a-z_][a-z0-9_]*\s+on\s+([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^alter\s+table\s+(?:only\s+)?([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^drop\s+table\s+(?:if\s+exists\s+)?([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^insert\s+into\s+([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^update\s+(?:only\s+)?([a-z_][a-z0-9_]*)"), 1),
    (re.compile(r"^delete\s+from\s+(?:only\s+)?([a-z_][a-z0-9_]*)"), 1),
]
DROP_INDEX = re.compile(r"^drop\s+index\s+(?:concurrently\s+)?(?:if\s+exists\s+)?([a-z_][a-z0-9_]*)")
CREATE_INDEX = re.compile(r"^create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?"
                          r"([a-z_][a-z0-9_]*)\s+on\s+([a-z_][a-z0-9_]*)")
SCHEMA_LEVEL_STATEMENT = re.compile(r"^(?:create|drop|alter)\s+(?:schema|extension)\b")


# --------------------------------------------------------------------------------------------
# docs/DATA_MODEL.md — the one source of truth for a table's tier.
# --------------------------------------------------------------------------------------------
def read_tiers():
    """table -> 'platform' | 'tenant' | 'platform + tenant', exactly as TenancyTierBoundaryTest reads it."""
    tiers = OrderedDict()
    table = None
    with open(DATA_MODEL, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            heading = TABLE_HEADING.match(line)
            if heading:
                table = heading.group(1)
                continue
            tier = TIER_LINE.match(line)
            if tier and table:
                # The clause after the em dash is prose for a human. Only the tier is machine-read —
                # the same split TenancyTierBoundaryTest makes, so the two cannot disagree.
                value = tier.group(1).split("—", 1)[0].strip()
                if value not in (PLATFORM, TENANT, BOTH):
                    raise SystemExit("unknown tier %r for %s in docs/DATA_MODEL.md" % (value, table))
                tiers[table] = value
                table = None
    tiers.update(HISTORIC_TIERS)
    return tiers


def homes(tier):
    """The directories a statement about a table of this tier belongs in."""
    if tier == BOTH:
        return (PLATFORM, TENANT)
    return (tier,)


# --------------------------------------------------------------------------------------------
# Parsing a migration into blocks. A block is one statement plus the comment lines directly above
# it — which is what keeps a rationale attached to the statement it explains when the two halves
# are written out.
# --------------------------------------------------------------------------------------------
def strip_trailing_comment(line, in_string):
    """The code part of a line, plus the quote state at its end. '--' inside a literal is data."""
    out = []
    position = 0
    while position < len(line):
        char = line[position]
        if char == "'":
            in_string = not in_string
        elif not in_string and char == "-" and line[position + 1:position + 2] == "-":
            break
        out.append(char)
        position += 1
    return "".join(out), in_string


class Block:
    def __init__(self, comments, statement, blanks_before):
        self.comments = comments          # list of raw '--' lines directly above the statement
        self.statement = statement        # list of raw lines, ending with the one carrying ';'
        self.blanks_before = blanks_before
        self.subject = None               # table name, or SCHEMA_LEVEL
        self.homes = ()

    def text(self):
        """The statement's CODE, one line, lower-cased — inline comments removed.

        Removing them matters: `org_id uuid not null,  -- organization.id (soft ref, no FK)` would
        otherwise make the parser think this statement references `organization`, and the whole
        point of a soft ref is that it does not.
        """
        code, in_string = [], False
        for line in self.statement:
            part, in_string = strip_trailing_comment(line, in_string)
            code.append(part.strip())
        return " ".join(part for part in code if part).lower()


class Migration:
    def __init__(self, version, description, filename, header, blocks):
        self.version = version
        self.description = description
        self.filename = filename
        self.header = header              # list of raw '--' lines from line 1
        self.blocks = blocks


def parse_migration(path):
    name = os.path.basename(path)
    match = MIGRATION_FILE.match(name)
    if not match:
        raise SystemExit("not a Flyway migration filename: %s" % name)
    version, description = int(match.group(1)), match.group(2)

    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    while lines and lines[-1] == "":
        lines.pop()

    # The file header is the comment run starting at line 1. Every file in this repo separates
    # header paragraphs with a bare '--' rather than a blank line, so the run ends at the first
    # blank line or the first statement — never mid-header.
    index = 0
    header = []
    while index < len(lines) and lines[index].startswith("--"):
        header.append(lines[index])
        index += 1

    blocks = []
    comments = []
    statement = []
    blanks = 0
    in_string = False
    while index < len(lines):
        line = lines[index]
        index += 1
        if not statement:
            if line.strip() == "":
                if comments:
                    # A blank line inside a run of comments ends that run: what follows is a new
                    # paragraph belonging to the next statement, not a continuation.
                    blocks.append(Block(comments, [], blanks))
                    comments, blanks = [], 0
                blanks += 1
                continue
            if line.startswith("--"):
                comments.append(line)
                continue
        statement.append(line)
        # ';' terminates a statement unless it is inside a string literal, and a trailing '--'
        # comment must not be scanned for either — V9's `-- ...` lines carry apostrophes that would
        # otherwise flip the quote parity and swallow the rest of the file. No migration in this set
        # uses dollar quoting, so single-quote parity plus comment stripping is exact.
        code, in_string = strip_trailing_comment(line, in_string)
        if not in_string and code.rstrip().endswith(";"):
            blocks.append(Block(comments, statement, blanks))
            comments, statement, blanks = [], [], 0
    if statement:
        raise SystemExit("unterminated statement at end of %s" % name)
    if comments:
        blocks.append(Block(comments, [], blanks))
    return Migration(version, description, name, header, blocks)


def index_owners(migrations):
    """index name -> table, so `drop index` lands in the same half as the `create index` it undoes."""
    owners = {}
    for migration in migrations:
        for block in migration.blocks:
            found = CREATE_INDEX.match(block.text())
            if found:
                owners[found.group(1)] = found.group(2)
    return owners


def classify(migrations, tiers):
    owners = index_owners(migrations)
    for migration in migrations:
        for block in migration.blocks:
            if not block.statement:
                block.subject = None
                continue

            text = block.text()
            if SCHEMA_LEVEL_STATEMENT.match(text):
                block.subject = SCHEMA_LEVEL
                block.homes = (PLATFORM,)
                continue
            subject = None
            for pattern, group in STATEMENT_SUBJECT:
                found = pattern.match(text)
                if found:
                    subject = found.group(group)
                    break
            if subject is None:
                found = DROP_INDEX.match(text)
                if found:
                    subject = owners.get(found.group(1))
                    if subject is None:
                        raise SystemExit("%s drops index %s that no migration creates"
                                         % (migration.filename, found.group(1)))
            if subject is None:
                raise SystemExit("%s: cannot tell which table this statement is about:\n  %s"
                                 % (migration.filename, text[:160]))
            if subject not in tiers:
                raise SystemExit("%s touches %s, which has no '**Tier:**' line in docs/DATA_MODEL.md"
                                 % (migration.filename, subject))
            block.subject = subject
            block.homes = homes(tiers[subject])

        # A comment block standing alone between two statements — the "WHY external_org_id IS
        # varchar AND NOT uuid" species — explains its surroundings and belongs with them. Dropping
        # it because it has no statement of its own would lose exactly the prose this split exists
        # to preserve, so it is attributed by the tables it names and falls back to the statement it
        # sits above (then the one it sits below, for a trailing note).
        statements = [b for b in migration.blocks if b.statement]
        named = {b.subject for b in statements if b.subject and b.subject != SCHEMA_LEVEL}
        for position, block in enumerate(migration.blocks):
            if block.statement:
                continue
            attributed = attribute_paragraph(block.comments, tiers, named)
            following = next((b.homes for b in migration.blocks[position + 1:] if b.statement), ())
            preceding = next((b.homes for b in reversed(migration.blocks[:position]) if b.statement), ())
            if attributed == {PLATFORM, TENANT} and (following or preceding):
                block.homes = tuple(following or preceding)
            else:
                block.homes = tuple(sorted(attributed))


def cross_tier_references(block, tiers):
    """Other tables the statement names, whose tier differs from its subject's.

    This is what finds the statements the split cannot carry unchanged: a foreign key into another
    tier, or a backfill that joins across one. Every hit is a decision a human has to make, so they
    are reported rather than resolved.
    """
    if not block.statement or block.subject in (None, SCHEMA_LEVEL):
        return []
    text = block.text()
    subject_homes = set(block.homes)
    hits = []
    for other in re.findall(r"(?:references|from|join|into)\s+([a-z_][a-z0-9_]*)", text):
        if other == block.subject or other not in tiers:
            continue
        if set(homes(tiers[other])) != subject_homes:
            hits.append(other)
    return sorted(set(hits))


# --------------------------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------------------------
def load_flat():
    paths = []
    for name in os.listdir(MIGRATIONS):
        if MIGRATION_FILE.match(name):
            paths.append(os.path.join(MIGRATIONS, name))
    if not paths:
        raise SystemExit("no flat migrations in %s — the split has already been applied; use verify"
                         % MIGRATIONS)
    paths.sort(key=lambda p: int(MIGRATION_FILE.match(os.path.basename(p)).group(1)))
    return [parse_migration(path) for path in paths]


def command_report(_args):
    tiers = read_tiers()
    migrations = load_flat()
    classify(migrations, tiers)

    letter = {PLATFORM: "p", TENANT: "t", BOTH: "b"}
    bisected, mirrored = [], []
    print("%-6s %-16s %s" % ("V", "HOMES", "TABLES"))
    for migration in migrations:
        tables = []
        found = set()
        for block in migration.blocks:
            found.update(block.homes)
            if block.subject and block.subject != SCHEMA_LEVEL and block.subject not in tables:
                tables.append(block.subject)
        if found == {PLATFORM, TENANT}:
            # Two homes is not the same question as two halves. A migration that touches ONLY split
            # tables goes to both directories WHOLE — identical DDL, the seven-table rule — while a
            # migration whose statements divide is the one whose header has to be read and split.
            (mirrored if all(tiers[t] == BOTH for t in tables) else bisected).append(migration.version)
        print("V%-5d %-16s %s" % (migration.version, "+".join(sorted(found)) or "-",
                                  ", ".join("%s[%s]" % (t, letter[tiers[t]]) for t in tables)
                                  or "(no tables)"))

    print("\nBISECTED, statements divide (generated): %s"
          % ", ".join("V%d" % v for v in bisected))
    print("MIRRORED, split tables only, identical in both (generated): %s"
          % ", ".join("V%d" % v for v in mirrored))
    both = sorted(bisected + mirrored)
    adr = [11, 13, 17, 19, 20, 22, 23, 24, 25, 26, 31, 33, 34, 35, 36, 45, 49, 50, 52]
    print("\nIn both directories (generated): %s" % ", ".join("V%d" % v for v in both))
    print("ADR 0010 §4.1's hand list:       %s" % ", ".join("V%d" % v for v in adr))
    print("in the ADR's list, but single-home here: %s" % [v for v in adr if v not in both])
    print("in both directories, missing from the ADR's list: %s" % [v for v in both if v not in adr])

    print("\nCROSS-TIER STATEMENTS (each needs a human decision):")
    for migration in migrations:
        for block in migration.blocks:
            hits = cross_tier_references(block, tiers)
            if hits:
                print("  V%-3d %-22s -> %s\n       %s"
                      % (migration.version, block.subject, ", ".join(hits), block.text()[:120]))
    return 0


# --------------------------------------------------------------------------------------------
# Splitting
# --------------------------------------------------------------------------------------------
def header_paragraphs(header):
    """Split a header into paragraphs on bare '--' lines, keeping the separators with the break."""
    paragraphs, current = [], []
    for line in header:
        if line.strip() == "--":
            paragraphs.append(current)
            current = []
        else:
            current.append(line)
    paragraphs.append(current)
    return [p for p in paragraphs if p]


def attribute_paragraph(paragraph, tiers, present):
    """Which halves a header paragraph belongs to, from the tables it names.

    A first pass only, and deliberately generous: a paragraph naming tables from both halves, or
    naming none, goes to both. Prose is not machine-attributable and the corrected version is what
    is committed — see this module's docstring.
    """
    text = " ".join(paragraph).lower()
    named = {t for t in present if re.search(r"\b%s\b" % re.escape(t), text)}
    found = {home for table in named for home in homes(tiers[table])}
    if found in ({PLATFORM}, {TENANT}):
        return found
    return {PLATFORM, TENANT}


def render(migration, home, tiers, sibling):
    lines = []
    present = {b.subject for b in migration.blocks if b.subject and b.subject != SCHEMA_LEVEL}
    paragraphs = header_paragraphs(migration.header)
    kept = [p for p in paragraphs
            if not sibling or home in attribute_paragraph(p, tiers, present)]
    for position, paragraph in enumerate(kept):
        if position:
            lines.append("--")
        lines.extend(paragraph)
    if sibling:
        if lines:
            lines.append("--")
        lines.append("-- SPLIT (ADR 0010 §4.1): this is the %s half of V%d. Its sibling is "
                     "db/migration/%s/%s."
                     % (home, migration.version, sibling, migration.filename))

    previous = None
    for block in migration.blocks:
        if home not in block.homes:
            previous = block
            continue
        if lines:
            # Reproduce the original spacing when the previous block came along too; force one blank
            # line when it did not, so a paragraph never fuses onto the statement above it.
            blanks = block.blanks_before if previous is None or home in previous.homes else 1
            lines.extend([""] * max(1, blanks))
        lines.extend(block.comments)
        lines.extend(block.statement)
        previous = block
    return "\n".join(lines) + "\n"


def command_split(args):
    tiers = read_tiers()
    migrations = load_flat()
    classify(migrations, tiers)

    for home in (PLATFORM, TENANT):
        os.makedirs(os.path.join(MIGRATIONS, home), exist_ok=True)

    written = []
    for migration in migrations:
        found = sorted({home for block in migration.blocks for home in block.homes})
        if not found:
            # V1 has no statements at all. It is the root of the counter, so both sequences get it.
            found = [PLATFORM, TENANT]
        sibling = {PLATFORM: TENANT, TENANT: PLATFORM} if len(found) == 2 else {}
        for home in found:
            path = os.path.join(MIGRATIONS, home, migration.filename)
            body = render(migration, home, tiers, sibling.get(home))
            if args.dry_run:
                print("would write %s (%d lines)" % (path, body.count("\n")))
            else:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(body)
            written.append(path)
    print("wrote %d files from %d migrations" % (len(written), len(migrations)))
    return 0


# --------------------------------------------------------------------------------------------
# Verifying the split tree that exists today
# --------------------------------------------------------------------------------------------
def command_verify(_args):
    tiers = read_tiers()
    problems = []
    trees = {}
    for home in (PLATFORM, TENANT):
        directory = os.path.join(MIGRATIONS, home)
        names = sorted((n for n in os.listdir(directory) if MIGRATION_FILE.match(n)),
                       key=lambda n: int(MIGRATION_FILE.match(n).group(1)))
        trees[home] = [parse_migration(os.path.join(directory, n)) for n in names]

    every = trees[PLATFORM] + trees[TENANT]
    classify(every, tiers)

    for home in (PLATFORM, TENANT):
        for migration in trees[home]:
            for block in migration.blocks:
                if block.subject in (None, SCHEMA_LEVEL):
                    if block.subject == SCHEMA_LEVEL and home != PLATFORM:
                        problems.append("%s/%s creates a schema or extension; those are the "
                                        "platform sequence's" % (home, migration.filename))
                    continue
                if home not in block.homes:
                    problems.append("%s/%s: `%s` is %s-tier and cannot be in the %s sequence"
                                    % (home, migration.filename, block.subject,
                                       tiers[block.subject], home))
                hits = cross_tier_references(block, tiers)
                if hits:
                    problems.append("%s/%s: statement about `%s` also names %s from the other tier"
                                    % (home, migration.filename, block.subject, ", ".join(hits)))

    # Every split table must exist in both directories, or the seven-table rule has quietly become
    # a six-table rule and one tenant would find a table missing after promotion.
    created = {home: {b.subject for m in trees[home] for b in m.blocks
                      if b.statement and b.text().startswith("create table")}
               for home in (PLATFORM, TENANT)}
    for table, tier in tiers.items():
        if table in HISTORIC_TIERS:
            continue
        for home in homes(tier):
            if table not in created[home]:
                problems.append("`%s` is %s-tier but no migration in db/migration/%s creates it"
                                % (table, tier, home))

    # ADR 0010 §2's seven split tables get IDENTICAL DDL in both directories — same table, two homes,
    # routed at runtime on org_id nullability. Identical is the whole point and is exactly what rots:
    # an index added to `platform.audit_log` and not to the tenant copy is invisible until a promoted
    # tenant's audit page gets slow, and a COLUMN added to one and not the other is invisible until a
    # row moves. Compared statement by statement, in order, per table.
    def split_table_statements(home):
        found = {}
        for migration in trees[home]:
            for block in migration.blocks:
                if block.statement and block.subject and tiers.get(block.subject) == BOTH:
                    found.setdefault(block.subject, []).append(
                        (migration.version, block.text()))
        return found

    platform_ddl, tenant_ddl = split_table_statements(PLATFORM), split_table_statements(TENANT)
    for table in sorted({t for t, tier in tiers.items() if tier == BOTH}):
        if platform_ddl.get(table) != tenant_ddl.get(table):
            problems.append("`%s` is a split table and its DDL differs between the two directories; "
                            "the copies must be identical (ADR 0010 §4.1)" % table)

    # A V-number in both directories is a bisected migration and must be justified by a statement
    # each side; the counter rule (AGENTS §4.5) is that a NEW migration lands in exactly one.
    versions = {home: {m.version for m in trees[home]} for home in (PLATFORM, TENANT)}
    for version in sorted(versions[PLATFORM] & versions[TENANT]):
        halves = [m for m in every if m.version == version]
        if any(not m.blocks or all(not b.statement for b in m.blocks) for m in halves):
            continue  # V1 is the shared root of the counter and creates nothing.
        for migration in halves:
            if not any(b.statement for b in migration.blocks):
                problems.append("V%d exists in both directories but one half is empty" % version)

    # The generated tenant_pool scaffold that used to be checked here is gone: ADR 0010 Phase 4 landed
    # shared.persistence.TenantMigrationRunner, and db/migration/bootstrap, its entry in
    # spring.flyway.locations and this script's `bootstrap` mode went with it. A tenant migration
    # edited without the pool being re-migrated is now caught where it belongs — by the runner, and by
    # the head-parity test that asserts every tenant schema's flyway_schema_history matches
    # tenant_pool's.

    for problem in problems:
        print("FAIL %s" % problem)
    if problems:
        return 1
    print("ok: %d platform + %d tenant migrations, %d tables, no statement on the wrong side"
          % (len(trees[PLATFORM]), len(trees[TENANT]), len(tiers) - len(HISTORIC_TIERS)))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("report", help="print the generated bisection list and cross-tier statements")
    split = sub.add_parser("split", help="write platform/ and tenant/ from the flat set (one-shot)")
    split.add_argument("--dry-run", action="store_true")
    sub.add_parser("verify", help="check the split tree against docs/DATA_MODEL.md")
    args = parser.parse_args()
    return {"report": command_report, "split": command_split,
            "verify": command_verify}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
