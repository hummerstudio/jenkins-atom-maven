#!/usr/bin/env bash
# Run the atom the way the runner does, against a fake mvn on PATH: no Jenkins, no toolchain, CI-friendly.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=7 JOB_NAME="$(basename "$PWD")-job"

./build.sh >/dev/null
jar="$(cd dist/maven && pwd)/maven.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

bin="$(mktemp -d)"
work="$(mktemp -d)"
cat >"$bin/mvn" <<'FAKE'
#!/usr/bin/env bash
echo "fake mvn called with: $*"
echo "workspace=$PWD"
case "$*" in
  *explode*) exit 7 ;;
esac
exit 0
FAKE
chmod +x "$bin/mvn"

run() { # run <dir>
  local dir="$1"
  python3 - "$dir/input.json" "$dir" <<'JSONFIX'
import json, os, sys
path, workspace = sys.argv[1], sys.argv[2]
given = {}
if os.path.exists(path) and os.path.getsize(path):
    given = json.load(open(path))
# the runner writes its environment into input.json first, then the job's inputs on top
data = dict(os.environ)
data.update(given)
data.setdefault("atom_workspace", workspace)
json.dump(data, open(path, "w"))
JSONFIX
  ( cd "$dir" && unset MAVEN_HOME && PATH="$bin:$PATH" atom_data_dir="$dir" atom_data_input=input.json \
      atom_data_output=output.json atom_workspace="$dir" BUILD_NUMBER=7 \
      "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}


fail() { echo "smoke failed: $*" >&2; exit 1; }

# 1. goals and options reach the tool, the workspace is the job workspace, exit 0 is success
dir="$work/ok"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"goals": "verify -Dbuild=${BUILD_NUMBER}", "options": "-Pci", "home": "", "failOnError": "true"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "call failed: $(cat "$dir/log.txt")"
grep -q "fake mvn called with: verify -Dbuild=7 -Pci" "$dir/log.txt" \
  || fail "arguments did not reach the tool: $(cat "$dir/log.txt")"
grep -q "workspace=$dir" "$dir/log.txt" || fail "tool did not run in the workspace"
grep -q '"status":"success"' "$dir/output.json" || fail "expected success: $(cat "$dir/output.json")"
grep -q '"exitCode":{"type":"string","value":"0"}' "$dir/output.json" || fail "exit code missing"

# 2. a non-zero exit fails the atom and is reported with the code
dir="$work/explode"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"goals": "explode", "options": "", "home": "", "failOnError": "true"}
JSON
if run "$dir" >"$dir/log.txt" 2>&1; then fail "exit 7 must fail the atom"; fi
grep -q '"status":"failure"' "$dir/output.json" || fail "failure not reported: $(cat "$dir/output.json")"
grep -q 'mvn exited with 7' "$dir/output.json" || fail "exit code is not in the message"

# 3. failOnError=false turns a non-zero exit into a success with the code still reported
dir="$work/tolerate"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"goals": "explode", "options": "", "home": "", "failOnError": "false"}
JSON
run "$dir" >/dev/null 2>&1 || fail "failOnError=false must not fail the atom"
grep -q '"exitCode":{"type":"string","value":"7"}' "$dir/output.json" || fail "exit code lost"

# 4. an unknown home falls back to PATH, and a home that does not contain the tool does not crash
dir="$work/badhome"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"goals": "verify", "options": "", "home": "/definitely/not/here", "failOnError": "true"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "missing home must fall back to PATH: $(cat "$dir/log.txt")"
grep -q "fake mvn called" "$dir/log.txt" || fail "fallback to PATH did not happen"

echo "smoke ok: arguments, workspace, exit code, tolerate mode, PATH fallback"
