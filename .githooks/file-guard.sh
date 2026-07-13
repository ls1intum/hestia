#!/bin/sh
# Blocks newly added binary files (PDFs, images, archives, ...) from entering the repo.
# Lecture PDFs are copyrighted and binaries do not diff or review well.
#
# Usage:
#   file-guard.sh --staged            # check files staged for commit (pre-commit hook)
#   file-guard.sh <base> <head>       # check files added between two revisions (CI)
#
# Deliberate exceptions (e.g. a favicon, a synthetic test fixture): add the exact
# repo-relative path to .githooks/binary-allowlist in the same commit, so the
# exception is visible in review and passes both the hook and CI.

set -eu

allowlist_file=$(dirname "$0")/binary-allowlist

if [ "${1:-}" = "--staged" ]; then
    added=$(git diff --cached --name-only --diff-filter=A)
    numstat=$(git diff --cached --numstat --diff-filter=A)
elif [ $# -eq 2 ]; then
    added=$(git diff --name-only --diff-filter=A "$1...$2")
    numstat=$(git diff --numstat --diff-filter=A "$1...$2")
else
    echo "usage: file-guard.sh --staged | file-guard.sh <base> <head>" >&2
    exit 2
fi

[ -n "$added" ] || exit 0

# Extension blocklist (catches binary formats even when the file is small or empty).
# SVGs are plain text and stay allowed.
blocked_ext=$(printf '%s\n' "$added" | grep -iE \
    '\.(pdf|pptx?|docx?|xlsx?|zip|jar|7z|rar|tar|gz|tgz|bz2|xz|dump|sqlite3?|db|bin|exe|dll|so|dylib|class|png|jpe?g|gif|bmp|ico|webp|heic|mp[34]|mov|avi|woff2?|ttf|otf|eot)$' || true)

# Content check: anything git itself treats as binary ("-" line counts in numstat).
blocked_bin=$(printf '%s\n' "$numstat" | awk -F'\t' '$1 == "-" && $2 == "-" { print $3 }')

offenders=$(printf '%s\n%s\n' "$blocked_ext" "$blocked_bin" | sed '/^$/d' | sort -u)

# Drop paths that are explicitly allowlisted (exact match, comments/blanks ignored).
if [ -n "$offenders" ] && [ -f "$allowlist_file" ]; then
    allowed=$(sed 's/#.*//; s/[[:space:]]*$//; /^$/d' "$allowlist_file")
    offenders=$(printf '%s\n' "$offenders" | grep -Fxv "$allowed" || true)
fi

if [ -n "$offenders" ]; then
    {
        echo "file-guard: refusing to add binary files:"
        printf '%s\n' "$offenders" | sed 's/^/  - /'
        echo
        echo "This repo takes no new PDFs or other binary files (copyright + reviewability)."
        echo "For a deliberate, reviewed exception, add the exact path to .githooks/binary-allowlist"
        echo "in the same commit."
    } >&2
    exit 1
fi
