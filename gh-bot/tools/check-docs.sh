#!/usr/bin/env bash
# Doc-consistency check — kills README/changelog drift before it ships.
#
# Verifies:
#   1. build.gradle.kts `version` == the jar referenced in both README headers
#   2. The smoke-count claims in README.md / gh-bot/README.md headers match the
#      actual smoke result (pass it as $1; if omitted, re-runs nothing and only
#      checks that both READMEs AGREE with each other and with COMMANDS.md's
#      version header).
#   3. COMMANDS.md header names the current version.
#
# Usage:
#   bash tools/check-docs.sh            # internal consistency only
#   bash tools/check-docs.sh 420        # also assert the smoke count == 420
# In CI: tee the SmokeTest stdout to a file, parse "(NNN passed", call this with NNN.
set -euo pipefail
cd "$(dirname "$0")/.."
cd ..   # repo root (README.md + gh-bot/ + ai-builder-bot-plan.md live here)

fail() { echo "[DOCS] FAIL: $*" >&2; exit 1; }

VER=$(grep -m1 '^version = ' gh-bot/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
[ -n "$VER" ] || fail "could not read version from gh-bot/build.gradle.kts"
echo "[DOCS] version in build.gradle.kts: $VER"

grep -q "GHBot-$VER.jar" README.md         || fail "README.md header does not name GHBot-$VER.jar"
grep -q "GHBot-$VER.jar" gh-bot/README.md  || fail "gh-bot/README.md header does not name GHBot-$VER.jar"
grep -q "(v$VER " gh-bot/COMMANDS.md || fail "gh-bot/COMMANDS.md header does not say v$VER"

# Smoke counts claimed in the two README Status headers must agree with each other…
claim_root=$(grep -m1 -o 'smoke [0-9]*/[0-9]* PASS' README.md | grep -o '^smoke [0-9]*' | awk '{print $2}')
claim_gh=$(grep -m1 -o 'smoke \*\*[0-9]*/[0-9]* PASS\*\*' gh-bot/README.md | grep -o '[0-9]*' | head -1)
[ -n "$claim_root" ] || fail "README.md header has no 'smoke N/N PASS' claim"
[ -n "$claim_gh" ]   || fail "gh-bot/README.md header has no 'smoke **N/N PASS**' claim"
[ "$claim_root" = "$claim_gh" ] || fail "smoke claims disagree: README.md=$claim_root vs gh-bot/README.md=$claim_gh"
echo "[DOCS] smoke-claim in both READMEs: $claim_root (consistent)"

# … and, if given, with the actual suite result.
if [ $# -ge 1 ]; then
  [ "$claim_root" = "$1" ] || fail "README smoke claim ($claim_root) != actual smoke result ($1)"
  echo "[DOCS] smoke-claim matches actual suite result: $1"
fi

# v0.22.4 — the shipped jar's plugin.yml stamp must equal the build version
# (v0.22.3 shipped a stale '0.22.2' stamp: Gradle re-jarred new code over cached
# processResources output. `version GHBot` reported 0.22.2 on the owner's server).
JAR="releases/GHBot-$VER.jar"
if [ -f "$JAR" ]; then
  STAMP=$(unzip -p "$JAR" plugin.yml 2>/dev/null | grep -m1 '^version:' | sed -E "s/^version:[[:space:]]*'?([^']*)'.*/\1/")
  [ "$STAMP" = "$VER" ] || fail "$JAR plugin.yml says '$STAMP' (expected '$VER') — stale stamp, rebuild"
  echo "[DOCS] releases jar stamp: $STAMP (matches)"
fi

echo "[DOCS] OK — docs consistent (v$VER)."
