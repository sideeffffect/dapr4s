#!/bin/sh
# Runs scala-cli (pinned version, on the JVM via coursier) with its temp directory on tmpfs.
#
# WHAT: `cs launch scala-cli:<version>` with -Djava.io.tmpdir pointing into /dev/shm.
#
# WHY: scala-cli (at least 1.14.0, modules/cli/.../package0/Package.scala) chooses the Scala.js
# entry module of a multi-file link as `os.walk.stream(linkingDir).filter(_.ext == "js")` —
# for a test link (no main class) it takes the FIRST .js file in directory-listing order. The
# Wasm backend emits two: main.js (the entry) and __loader.js (a helper). Listing order is a
# filesystem property: on tmpfs (newest first) main.js comes first, on ext4 (hash order) it
# can be __loader.js. When scala-cli imports __loader.js, the Scala.js test bridge never
# starts and Node waits forever on the test-adapter socket — the JS integration leg hangs
# silently. GitHub's ubuntu-24.04 runners have /tmp on ext4 and hit exactly that ordering;
# most dev machines have /tmp on tmpfs and never see it. The linking directory comes from
# os.temp.dir, i.e. java.io.tmpdir, which the native launcher cannot override — hence the
# JVM launcher.
#
# WHY IT IS SAFE: only scala-cli's scratch files move to tmpfs; inputs, outputs and the
# toolchain version are unchanged. If scala-cli ever stops relying on listing order, this is
# a harmless no-op and can be deleted.
#
# Usage: SCALA_CLI=scripts/scala-cli-tmpfs.sh scripts/wasm-test.sh ...   (test-js-integration.sh
# selects it automatically). SCALA_CLI_VERSION overrides the pinned version (keep it in sync
# with `scala-cli-version` in .github/workflows/ci.yml).
set -eu
tmp="${SCALA_CLI_TMPFS_DIR:-/dev/shm/dapr4s-scala-cli-tmp}"
mkdir -p "$tmp"
exec cs launch --java-opt "-Djava.io.tmpdir=$tmp" "scala-cli:${SCALA_CLI_VERSION:-1.14.0}" -- "$@"
