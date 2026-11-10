#!/usr/bin/env bash
# Build an UNRELEASED scala-wasm from source and publishLocal it (to ~/.ivy2/local) as version
# $SCALA_WASM_VERSION. The Mill build uses exactly this version (`Dapr4sWasm.ScalaWasmVersion` in
# mill-build/src/dapr4s/millwasm/Dapr4sWasmModules.scala; mill-dapr4s-wasm and sbt-dapr4s-wasm resolve it).
#
# WHAT: the scala-wasm/scala-wasm `scala-wasm` branch at $BASE, the merge of PR #271. It contains the
# three upstream PRs dapr4s depends on, all merged:
#   - https://github.com/scala-wasm/scala-wasm/pull/268: WIT params through linear memory beyond 16 flat
#     params, both sides. Retires dapr4s's patched linker.
#   - https://github.com/scala-wasm/scala-wasm/pull/269: keep @WitAlias behind a plain type alias.
#     Retires the WIT re-embed (`component embed` + `component new`) in both build plugins.
#   - https://github.com/scala-wasm/scala-wasm/pull/271: the sbt plugin excludes the upstream
#     org.scala-js core libraries. Retires those exclusions in sbt-dapr4s-wasm (Mill keeps its own).
# WHY: those fixes are merged but not in a scala-wasm release yet (newest on Central: 1.22.1-wasm.6).
# The build is the upstream branch as is: no local merge, no patch (only the version string is set).
# RETIRES when a scala-wasm release contains all of them: pin that release in `Dapr4sWasm.ScalaWasmVersion`,
# and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (the SHA; the version is in its file name) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.scala-wasm keyed by this file's hash.
set -euo pipefail
SCALA_WASM_VERSION="${SCALA_WASM_VERSION:-1.22.1-wasm.7-dapr4s-local}"
BASE=d4e4febae26956b5c37a44b0382d3a9b70a61a60 # scala-wasm branch, "Merge pull request #271"
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}"
stampFile="$ivy/io.github.scala-wasm/dapr4s-build-stamp-$SCALA_WASM_VERSION"
stamp="$BASE"
plugin="$ivy/io.github.scala-wasm/sbt-scalajs/scala_2.12/sbt_1.0/$SCALA_WASM_VERSION/jars/sbt-scalajs.jar"
if [[ -f "$stampFile" && "$(cat "$stampFile")" == "$stamp" && -s "$plugin" && "${FORCE:-0}" != 1 ]]; then
  echo "build-scala-wasm.sh: $SCALA_WASM_VERSION up to date in $ivy (FORCE=1 to rebuild)"
  exit 0
fi
work="${WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/scala-wasm-snapshot-src}"
if [[ ! -d "$work/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/scala-wasm/scala-wasm.git "$work"
fi
cd "$work"
git fetch -q origin "$BASE"
git checkout -q -f --detach "$BASE"
git clean -q -fdx -e target
f=ir/shared/src/main/scala/org/scalajs/ir/ScalaJSVersions.scala
sed -i -E "s/current = \"[^\"]+\"/current = \"$SCALA_WASM_VERSION\"/" "$f"
grep -q "current = \"$SCALA_WASM_VERSION\"" "$f" || { echo "build-scala-wasm.sh: cannot set the version" >&2; exit 1; }
# The modules the builds resolve: the sbt plugin and its 2.12 dependencies (ir, linker-interface, linker, test
# adapter; sbt-dapr4s-wasm), javalib, and the 2.13 side (library, scalalib, compiler plugin, test bridge, and
# ir/linker-interface/linker/test adapter for Mill's Scala.js worker).
sbt -batch -no-colors \
  ir2_12/publishLocal linkerInterface2_12/publishLocal linker2_12/publishLocal testAdapter2_12/publishLocal \
  sbtPlugin2_12/publishLocal javalib/publishLocal javalibintf/publishLocal \
  ir2_13/publishLocal linkerInterface2_13/publishLocal linker2_13/publishLocal testAdapter2_13/publishLocal \
  library2_13/publishLocal scalalib2_13/publishLocal compiler2_13/publishLocal \
  testInterface2_13/publishLocal testBridge2_13/publishLocal jUnitRuntime2_13/publishLocal jUnitPlugin2_13/publishLocal
git checkout -q -- "$f"
[[ -s "$plugin" ]] || { echo "build-scala-wasm.sh: $plugin was not published" >&2; exit 1; }
echo "$stamp" >"$stampFile"
echo "build-scala-wasm.sh: published scala-wasm $SCALA_WASM_VERSION ($stamp) to $ivy"
