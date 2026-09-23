#!/usr/bin/env bash
# github-release.sh — (re)publish the GitHub Releases page entry for a shipped version.
#
# Owner release policy: the previous version is ALWAYS deleted — the GitHub
# Releases page, like releases/ in the repo, holds ONLY the newest version.
# This script:
#   1. deletes every existing release + its tag,
#   2. creates tag+release "v<ver>" at [sha] (default: main) with the given notes,
#   3. attaches releases/GHBot-<ver>.jar as the release asset.
#
# Usage:
#   GITHUB_TOKEN=<pat> bash gh-bot/tools/github-release.sh <ver> "<title>" <notes-file> [sha]
# Example:
#   GITHUB_TOKEN=ghp_... bash gh-bot/tools/github-release.sh 0.22.2 \
#     "GHBot v0.22.2 — Cmd output capture + audit fixes" /tmp/notes.md main
#
# NEVER hardcode the token — pass it via the GITHUB_TOKEN env var only.
set -euo pipefail

REPO="SG009/GHBot-Plugin"
API="https://api.github.com/repos/$REPO"
UP="https://uploads.github.com/repos/$REPO"

VER="${1:?usage: github-release.sh <ver> <title> <notes-file> [sha]}"
TITLE="${2:?missing release title}"
NOTES="${3:?missing notes file}"
SHA="${4:-main}"
TAG="v$VER"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/releases/GHBot-$VER.jar"
# the API rejects short/ambiguous target_commitish values — resolve to the full sha
FULL_SHA=$(git -C "$ROOT" rev-parse "$SHA" 2>/dev/null || echo "$SHA")

: "${GITHUB_TOKEN:?set GITHUB_TOKEN env var — never hardcode the token}"
[ -f "$NOTES" ] || { echo "notes file not found: $NOTES" >&2; exit 1; }
[ -f "$JAR" ]    || { echo "jar not found: $JAR (ship it to releases/ first)" >&2; exit 1; }

auth=(-H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

echo "== 1/3 delete existing releases + tags (policy: only the newest exists) =="
curl -sS "${auth[@]}" "$API/releases" | python3 -c '
import json,sys
for r in json.load(sys.stdin):
    print(r["id"], r["tag_name"])
' | while read -r rid rtag; do
  [ -n "$rid" ] || continue
  curl -sS -o /dev/null -w "  DELETE release $rid ($rtag): %{http_code}\n" -X DELETE "${auth[@]}" "$API/releases/$rid"
  curl -sS -o /dev/null -w "  DELETE tag $rtag: %{http_code}\n" -X DELETE "${auth[@]}" "$API/git/refs/tags/$rtag" || true
done

echo "== 2/3 create release $TAG @ $FULL_SHA =="
RESP=$(python3 -c '
import json,sys
print(json.dumps({"tag_name": sys.argv[1], "target_commitish": sys.argv[2],
                  "name": sys.argv[3], "body": sys.argv[4],
                  "draft": False, "prerelease": False}))
' "$TAG" "$FULL_SHA" "$TITLE" "$(cat "$NOTES")" | curl -sS -X POST "${auth[@]}" -H "Content-Type: application/json" -d @- "$API/releases")
REL_ID=$(printf '%s' "$RESP" | python3 -c '
import json,sys
d = json.load(sys.stdin)
if "id" not in d:
    sys.exit("API error creating release: " + json.dumps(d)[:500])
print(d["id"])
')
echo "  release id: $REL_ID"

echo "== 3/3 attach $(basename "$JAR") =="
curl -sS -X POST "${auth[@]}" -H "Content-Type: application/java-archive" \
  --data-binary @"$JAR" "$UP/releases/$REL_ID/assets?name=$(basename "$JAR")" \
  | python3 -c 'import json,sys; a=json.load(sys.stdin); print("  asset:", a["name"], a["size"], "bytes"); print("  url:  ", a["browser_download_url"])'

echo "DONE — Releases page now shows only $TAG"
