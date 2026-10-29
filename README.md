# jenkins-atom-maven

The [maven-plugin](https://plugins.jenkins.io/maven-plugin/) plugin (≈96k installations) as an **atom**: one Java file,
no plugin.

Runs `mvn <goals> <options>` in the job workspace and reports the exit code. The plugin's value was never the
wrapping — it was the tool, the log streaming and the fail-on-error rule, all of which are a few lines here.

* `goals` / `options` — `${ENV}` is expanded, so `-Dbuild=${BUILD_NUMBER}` works.
* `home` — a `mvn` install directory; empty means PATH, then `$MAVEN_HOME`.
* `failOnError` — default on; off keeps the exit code as an output and lets the build continue.

## Why an atom and not the plugin

The plugin variants each re-implement tool discovery, environment setup and result mapping, and they drift. This atom
is one file you can read in a minute, it appears in the atom center with a form, and a pipeline calls it with
`runAtom(atom: 'maven', version: '1.0.0', inputs: [goals: 'clean build'])`.

## Build and smoke test

```bash
./build.sh             # → dist/maven/{maven.jar,task.json}
./tools/smoke.sh       # fake mvn on PATH: arguments, workspace, exit code, tolerate mode, PATH fallback
```

## Publish

```bash
tools/publish-atom.sh --center <center-dir> --atom-dir dist/maven --version 1.0.0 \
    --key key.pem --key-id <keyId>     # tools/ 来自 jenkins-atom-plugin 仓库
```
