#!/usr/bin/env bash
#
# Compiles and runs NativesSmoke against one vision-natives jar.
#   tools/natives-smoke/run.sh <vision-natives-<classifier>.jar> <classifier>
#
# Fetches OpenFTC's AprilTag classes (the Java half the SDK uses) from Maven Central, unpacks the
# jar's natives for <classifier>, and runs the program with only those on java.library.path.
set -euo pipefail

jar=$1
classifier=$2
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Under MSYS2 the JDK is a Windows program: its own path, and every path handed to it, must be a
# Windows path. Elsewhere these are the identity.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
if command -v cygpath >/dev/null 2>&1; then JAVA_HOME=$(cygpath -u "$JAVA_HOME"); fi
jar_abs="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"

curl -fsSL -o "$work/apriltag.aar" \
  https://repo1.maven.org/maven2/org/openftc/apriltag/2.1.0/apriltag-2.1.0.aar
(cd "$work" && "$JAVA_HOME/bin/jar" xf apriltag.aar classes.jar)
mkdir -p "$work/natives" "$work/classes"
(cd "$work/natives" && "$JAVA_HOME/bin/jar" xf "$(win "$jar_abs")")

sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
"$JAVA_HOME/bin/javac" -d "$(win "$work/classes")" -cp "$(win "$work/classes.jar")" \
  "$(win "$here/NativesSmoke.java")"
"$JAVA_HOME/bin/java" -Djava.library.path="$(win "$work/natives/natives/$classifier")" \
  -cp "$(win "$work/classes")${sep}$(win "$work/classes.jar")" NativesSmoke
