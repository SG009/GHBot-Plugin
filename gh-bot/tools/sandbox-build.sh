#!/usr/bin/env bash
# Sandbox build + smoke (GITHUB-ONLY egress fallback).
#
# The dev sandbox can only reach github.com / api.github.com / codeload +
# registry.npmjs.org + pypi.org — NOT services.gradle.org, repo.papermc.io,
# maven central, or adoptium. So `tools/setup-build.sh` (Gradle) cannot run here.
# This script reproduces the same result with:
#   - JDK 21  runtime   -> pypi package `jdk4py` (Temurin 21 JRE, run-only)
#   - Java 21 compiler  -> Eclipse ECJ 3.37.0 (runs on the JRE, compiles to 21)
#   - paper-api 1.21.11 + deps -> a public repo's committed Gradle cache (real jars)
# Then it compiles src/ + SmokeTest.java and runs the headless smoke suite.
#
# Usage: bash tools/sandbox-build.sh          # compile + smoke
#        bash tools/sandbox-build.sh smoke    # smoke only (already compiled)
#        bash tools/sandbox-build.sh jar      # compile + build GHBot-<ver>.jar
set -euo pipefail
cd "$(dirname "$0")/.."

TMP=${TMPDIR:-/tmp}
VENV="$TMP/ghbot-venv"
JDK="$VENV/lib/python3.11/site-packages/jdk4py/java-runtime"
DEPS="$TMP/ghbot-deps"
ECJ="$TMP/ghbot-ecj/org/eclipse/jdt/ecj/3.37.0/ecj-3.37.0.jar"
CLASSES="$TMP/ghbot-classes"
SMOKE_CLASSES="$TMP/ghbot-smoke-classes"

# 1) JDK 21 (JRE) via jdk4py ------------------------------------------------
if [ ! -x "$JDK/bin/java" ]; then
  echo "[sandbox] installing JDK 21 runtime (jdk4py)…"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet "jdk4py==21.0.8.2"
fi

# 2) paper-api + dependency closure (real jars from a committed Gradle cache) --
if [ ! -d "$DEPS" ]; then
  echo "[sandbox] fetching paper-api + deps (sparse clone)…"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/NicolasLasch/ZoneGuard.git "$TMP/ghbot-zg"
  (cd "$TMP/ghbot-zg" && git sparse-checkout set .gradle-home/caches/modules-2/files-2.1)
  mv "$TMP/ghbot-zg/.gradle-home/caches/modules-2/files-2.1" "$DEPS"
  rm -rf "$TMP/ghbot-zg"
fi

# 3) ECJ 3.37 (Java 21 compiler, runs on the JRE) ---------------------------
if [ ! -f "$ECJ" ]; then
  echo "[sandbox] fetching ECJ 3.37 (sparse clone)…"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/hungn2k/nhap.git "$TMP/ghbot-nhap"
  (cd "$TMP/ghbot-nhap" && git sparse-checkout set org/eclipse/jdt/ecj/3.37.0)
  mkdir -p "$(dirname "$ECJ")"
  cp "$TMP/ghbot-nhap/org/eclipse/jdt/ecj/3.37.0/ecj-3.37.0.jar" "$ECJ"
  rm -rf "$TMP/ghbot-nhap"
fi

CP="$(find "$DEPS" -name '*.jar' | tr '\n' ':')"

if [ "${1:-}" != "smoke" ]; then
  echo "[sandbox] compiling plugin sources (ECJ, release 21)…"
  find src/main/java -name '*.java' | sort > "$TMP/ghbot-sources.txt"
  mkdir -p "$CLASSES" && rm -rf "$CLASSES"/*
  "$JDK/bin/java" -jar "$ECJ" -source 21 -target 21 -encoding UTF-8 -proc:none \
    -cp "$CP" -d "$CLASSES" @"$TMP/ghbot-sources.txt"
  echo "[sandbox] plugin compiled: $(find "$CLASSES" -name '*.class' | wc -l) classes"

  echo "[sandbox] compiling SmokeTest…"
  mkdir -p "$SMOKE_CLASSES" && rm -rf "$SMOKE_CLASSES"/*
  "$JDK/bin/java" -jar "$ECJ" -source 21 -target 21 -encoding UTF-8 -proc:none \
    -cp "$CLASSES:$CP" -d "$SMOKE_CLASSES" tools/SmokeTest.java
fi

if [ "${1:-}" = "jar" ]; then
  VER=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts | tr -d ' ')
  echo "[sandbox] packaging GHBot-$VER.jar…"
  python3 tools/make-jar.py "$CLASSES" src/main/resources "$VER" "build/libs/GHBot-$VER.jar"
  echo "[sandbox] jar ready at build/libs/GHBot-$VER.jar"
  exit 0
fi

echo "[sandbox] running smoke suite…"
mkdir -p "$TMP/javatmp"
"$JDK/bin/java" -Djava.io.tmpdir="$TMP/javatmp" \
  -cp "$SMOKE_CLASSES:$CLASSES:src/main/resources:$CP" dev.ghbot.SmokeTest
