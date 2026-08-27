# Documentation validation targets for repos without package.json
# Copy this file as `Makefile` (or include it in an existing Makefile)

DOCS_DIR ?= docs

# WAS: find $(DOCS_DIR) . -name '*.md' | grep -v node_modules | grep -v .git \
#        | grep -v generated | grep -v CHANGELOG.md
# That line carried THREE defects. All three are fixed by selecting with git
# instead of walking the filesystem by hand.
#
# 1. EVERY FILE MATCHED TWICE — once as `docs/x.md` and again as `./docs/x.md`,
#    because `find` was given both $(DOCS_DIR) and `.`. Error counts read
#    roughly double: rcg-architecture emitted 106 paths for 63 distinct files
#    and reported its 55-error baseline as 112 for weeks. `sort -u` does NOT
#    help — the two strings differ, so both survive.
#
# 2. GITIGNORED FILES WERE LINTED. Walking `.` picks up anything present on
#    disk, in or out of the repo. rcg-architecture linted 8 files under
#    .claude/. A developer box then goes red over a file that does not exist in
#    a fresh clone, and the fix for such a file can never be committed.
#
# 3. THE ONE NOBODY KNEW: `grep -v .git` has an UNESCAPED DOT. It is a regex,
#    so `.git` matches any character followed by "git" — including "/git". In
#    rcg-architecture that silently excluded docs/standards/git-workflow.md, a
#    load-bearing fleet standard, from BOTH gates for the entire life of the
#    file. Never counted, never reported, never checked: absence of a signal
#    read as absence of a problem. ANY repo with a path segment matching /.git/
#    loses that file the same way, silently.
#
# git ls-files emits each path exactly once and honours .gitignore, which kills
# 1 and 2; deleting the hand-rolled grep chain kills 3. git pathspecs are
# recursive, so 'docs/*.md' reaches docs/specs/x.md without -maxdepth games.

# UNTRACKED DOCS: IN SCOPE BY DEFAULT. `--others --exclude-standard` adds files
# that are new but not ignored. Without it a brand-new doc is skipped and the
# gate reports PASS on a file it never read.
#
# THE DEFAULT IS DELIBERATE, and the fleet already has one repo pointing each
# way, so read this before changing it:
#   * rcg-architecture has NO CI. Its `make docs-check` is the only gate there
#     is, so a silent PASS on an unread file is the failure mode that matters
#     most. It includes untracked.
#   * rcg-dev-install DELIBERATELY OMITS IT, because its line must match its
#     ci.yml command EXACTLY so the two gates cannot drift. Its own comment
#     records the incident: a local `-maxdepth 1` gate checked 21 files while
#     CI checked 23, so `make docs-check` passed on files CI then failed.
#
# Those two are not in conflict about the flag, they are optimising different
# things. rcg-dev-install's rule is that a local gate WEAKER than the remote one
# is worse than none, because it produces confident green — and `--others` makes
# the local gate STRONGER, never weaker. What it costs is exact parity.
#
# So: include it by default, because a repo adopting this template may well have
# no CI, and the two failure directions are not symmetric. Omitting it fails
# SILENTLY (a PASS on a file nobody read); including it fails LOUDLY and
# locally (a red on a doc you have not committed yet), which is visible and
# actionable. Set DOCS_UNTRACKED=no ONLY when this line must mirror a CI command
# verbatim — and then keep the two in sync by hand.
DOCS_UNTRACKED ?= yes
ifeq ($(DOCS_UNTRACKED),yes)
  _DOCS_LS_FLAGS := --cached --others --exclude-standard
else
  _DOCS_LS_FLAGS := --cached
endif

MD_FILES := $(shell git ls-files $(_DOCS_LS_FLAGS) '$(DOCS_DIR)/*.md' '*.md' 2>/dev/null | grep -v CHANGELOG.md)

# A gate that checks nothing passes. If git is absent, the repo is not a git
# checkout, or the pathspecs match nothing, MD_FILES is EMPTY — and markdownlint
# with no arguments exits 0, so docs-check would report PASS over zero files.
# That is the measures-nothing result, so fail loudly instead.
ifeq ($(strip $(MD_FILES)),)
  $(error no markdown files selected — is this a git checkout? \
    (DOCS_DIR=$(DOCS_DIR), DOCS_UNTRACKED=$(DOCS_UNTRACKED)). \
    A gate over zero files reports PASS and measures nothing.)
endif

.PHONY: docs-lint docs-spell docs-check

## Lint markdown files with markdownlint
docs-lint:
	@echo "Running markdownlint..."
	@npx --yes markdownlint-cli $(MD_FILES) && echo "markdownlint: PASS" || (echo "markdownlint: FAIL" && exit 1)

## Spell check markdown files with CSpell
docs-spell:
	@echo "Running CSpell..."
	@npx --yes cspell lint --config cspell.json $(MD_FILES) && echo "CSpell: PASS" || (echo "CSpell: FAIL" && exit 1)

## Run all doc checks (lint + spell)
docs-check: docs-lint docs-spell
	@echo ""
	@echo "All doc checks passed."
