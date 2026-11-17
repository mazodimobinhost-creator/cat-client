#!/usr/bin/env bash
# Review a change in its own git worktree, next to (not inside) your working copy — so a reviewer (a person, Grok,
# or any AI agent) reads and tests exactly that change without touching anyone's uncommitted work.
# Idea from herdr-redline (review in a git worktree beside an AI agent); written for this repository, no code copied.
#
#   scripts/review-worktree.sh <ref|pr-number> [--base <ref>]
#   scripts/review-worktree.sh 123                     # GitHub PR #123 (needs `gh` or a fetchable origin)
#   scripts/review-worktree.sh origin/some-branch --base origin/arena/c5b36768-cat-client
#   scripts/review-worktree.sh --clean                 # remove every review worktree this script made
#
# What it does (nothing is pushed, no branch is created — the worktree is detached):
#   1. git worktree add --detach /tmp/cat-review-<id> <ref>
#   2. inside it: codemap --check, Kotlin syntax gate (when tree-sitter is installed), node-panel copy check,
#      the panel tests that guard the changed files (scripts/affected-tests.py)
#   3. writes REVIEW.md there: diff stat, check results and the review checklist from docs/COLLABORATION.md,
#      plus REVIEW-PROMPT.md — paste it with the diff into the AI reviewer.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [[ "${1:-}" == "--clean" ]]; then
  git worktree list --porcelain | awk '/^worktree /{print $2}' | grep '/cat-review-' | while read -r wt; do
    echo "removing $wt"; git worktree remove --force "$wt" || true
  done
  git worktree prune
  exit 0
fi

REF="${1:?usage: scripts/review-worktree.sh <ref|pr-number> [--base <ref>]}"
shift
BASE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="${2:?--base needs a ref}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# Resolve a ref to a commit; in shallow clones fetch it (or deepen history) instead of failing.
resolve() {
  git rev-parse --verify -q "$1^{commit}" && return 0
  git fetch --quiet origin "$1" 2>/dev/null && git rev-parse FETCH_HEAD && return 0
  git fetch --quiet --deepen=300 2>/dev/null || true
  git rev-parse --verify -q "$1^{commit}"
}

if [[ "$REF" =~ ^[0-9]+$ ]]; then
  echo "fetching PR #$REF …"
  git fetch --quiet origin "pull/$REF/head"
  COMMIT="$(git rev-parse FETCH_HEAD)"
  if [[ -z "$BASE" ]] && command -v gh >/dev/null 2>&1; then
    BASE_NAME="$(gh pr view "$REF" --json baseRefName -q .baseRefName 2>/dev/null || true)"
    if [[ -n "$BASE_NAME" ]]; then git fetch --quiet origin "$BASE_NAME" && BASE="$(git rev-parse FETCH_HEAD)"; fi
  fi
  ID="pr$REF"
else
  COMMIT="$(resolve "$REF")" || { echo "cannot find $REF" >&2; exit 2; }
  ID="$(echo "$REF" | tr '/:' '--' | tr -cd 'A-Za-z0-9._-')"
fi
if [[ -n "$BASE" ]]; then
  BASE="$(resolve "$BASE")" || { echo "cannot find base $BASE" >&2; exit 2; }
else
  BASE="$(git merge-base "$COMMIT" HEAD 2>/dev/null)" || true
  [[ -n "$BASE" && "$BASE" != "$COMMIT" ]] || BASE="$(resolve "$COMMIT~1")" || { echo "no base found: pass --base <ref>" >&2; exit 2; }
fi

WT="/tmp/cat-review-$ID"
if [[ -d "$WT" ]]; then git worktree remove --force "$WT" 2>/dev/null || rm -rf "$WT"; fi
git worktree add --quiet --detach "$WT" "$COMMIT"
echo "worktree: $WT  (commit ${COMMIT:0:10}, base ${BASE:0:10})"
cd "$WT"

RESULTS=()
check() { # check "<name>" <command…>
  local name="$1"; shift
  if "$@" >"/tmp/cat-review-$ID.$(echo "$name" | tr -cd 'a-z0-9').log" 2>&1; then RESULTS+=("✅ $name"); else RESULTS+=("❌ $name (log: /tmp/cat-review-$ID.$(echo "$name" | tr -cd 'a-z0-9').log)"); fi
}

check "codemap" python3 scripts/codemap.py --check
if python3 -c 'import tree_sitter_kotlin' 2>/dev/null; then check "kotlin syntax" python3 scripts/kotlin-syntax-check.py app/src; else RESULTS+=("⏭️ kotlin syntax (pip install tree-sitter tree-sitter-kotlin)"); fi
check "node-panel copies identical" diff -r node-panel/index.js app/src/main/assets/node-panel/index.js
if [[ -d "$ROOT/node_modules" && ! -e node_modules ]]; then ln -s "$ROOT/node_modules" node_modules; fi
if command -v node >/dev/null 2>&1; then check "affected panel tests" python3 scripts/affected-tests.py --base "$BASE"; else RESULTS+=("⏭️ panel tests (node not installed)"); fi

CHANGED="$(git diff --name-only "$BASE" "$COMMIT" | wc -l | tr -d ' ')"
{
  echo "# Review of ${COMMIT:0:10} (base ${BASE:0:10})"
  echo
  echo "## Automatic checks"
  for r in "${RESULTS[@]}"; do echo "- $r"; done
  echo
  echo "## Changed files ($CHANGED)"
  echo '```'
  git diff --stat "$BASE" "$COMMIT" | tail -n 60
  echo '```'
  echo
  echo "## Checklist (docs/COLLABORATION.md → «چک‌لیست بازبینی»)"
  CHECKLIST="docs/COLLABORATION.md"
  [[ -f "$CHECKLIST" ]] || CHECKLIST="$ROOT/docs/COLLABORATION.md"
  if [[ -f "$CHECKLIST" ]]; then
    sed -n '/<!-- review-checklist:start -->/,/<!-- review-checklist:end -->/p' "$CHECKLIST" | sed '1d;$d'
  else
    echo "(docs/COLLABORATION.md not found)"
  fi
} > REVIEW.md

{
  echo "You are reviewing a change to Cat Client (Android VPN app + Cloudflare/Vercel/Node panels)."
  echo "Read REVIEW.md, then the diff (git diff $BASE $COMMIT). Report only real problems, each with file:line,"
  echo "why it breaks (crash, wrong config, lost data, security, a broken user path) and the smallest fix."
  echo "Hard rules of this repo you must check: docs/COLLABORATION.md («قوانین ثابت») — e.g. a scanned IP:port keeps"
  echo "exactly its port; ProxyIP/SOCKS ports never change; no other app names on screen; optional obfuscation off"
  echo "by default; no reset of UUID/KV/secrets without a backup; Fox VPN never falls back silently to direct."
} > REVIEW-PROMPT.md

printf '%s\n' "${RESULTS[@]}"
echo
echo "→ $WT/REVIEW.md and $WT/REVIEW-PROMPT.md"
echo "(remove it later with: scripts/review-worktree.sh --clean)"
