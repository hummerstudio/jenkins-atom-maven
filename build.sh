#!/usr/bin/env bash
# Compile the atom and pack dist/maven/ — the directory tools/publish-atom.sh expects.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac
  JAR=jar
fi

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
"$JAVAC" -encoding UTF-8 -d "$classes" src/MavenAtom.java

rm -rf dist/maven
mkdir -p dist/maven
"$JAR" --create --file dist/maven/maven.jar -C "$classes" .
cp task.json dist/maven/
[ -f meta.json ] && cp meta.json dist/maven/

echo "built dist/maven: $(ls dist/maven | tr '\n' ' ')"
