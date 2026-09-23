#!/usr/bin/env bash
# Restore the build toolchain into /tmp (wiped between turns) and run a gradle task.
# Usage: bash tools/setup-build.sh [gradle-task...]   (default: "jar")
set -e
cd "$(dirname "$0")/.."

# 1) JDK 21
if [ ! -x /tmp/jdk21/bin/java ]; then
  echo "[setup] downloading JDK 21…"
  curl -sL --max-time 500 -o /tmp/jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar -xzf /tmp/jdk21.tar.gz -C /tmp
  mv /tmp/jdk-21* /tmp/jdk21
  rm -f /tmp/jdk21.tar.gz
fi

# 2) Gradle 8.10.2
if [ ! -x /tmp/gradle/bin/gradle ]; then
  echo "[setup] downloading Gradle 8.10.2…"
  curl -sL --max-time 500 -o /tmp/gradle.zip \
    "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
  unzip -q /tmp/gradle.zip -d /tmp
  mv /tmp/gradle-8.10.2 /tmp/gradle
  rm -f /tmp/gradle.zip
fi

export JAVA_HOME=/tmp/jdk21
export PATH=/tmp/jdk21/bin:/tmp/gradle/bin:$PATH
export GRADLE_USER_HOME=/tmp/gradle-home
mkdir -p /tmp/gradle-home
export _JAVA_OPTIONS="-Djava.io.tmpdir=/tmp/javatmp"
mkdir -p /tmp/javatmp

echo "[setup] toolchain ready: $(java -version 2>&1 | head -1) · $(gradle -v 2>/dev/null | grep -m1 Gradle)"
exec gradle --no-daemon -q "${@:-jar}"
