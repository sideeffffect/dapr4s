#!/usr/bin/env bash
# Build an UNRELEASED scala-wasm from source and publishLocal it (to ~/.ivy2/local) as version
# $SCALA_WASM_VERSION. The wasm/ build (project/plugins.sbt, build.sbt) uses exactly this version.
#
# WHAT: the scala-wasm/scala-wasm `scala-wasm` branch at $BASE, plus a merge of the heads of the open
# upstream PRs dapr4s depends on:
#   - BASE includes https://github.com/scala-wasm/scala-wasm/pull/268 (merged): WIT params through
#     linear memory beyond 16 flat params, both sides. Retires dapr4s's patched linker.
#   - https://github.com/scala-wasm/scala-wasm/pull/269 (open): keep @WitAlias behind a plain type
#     alias. Retires the WIT re-embed (`component embed` + `component new`) in sbt-dapr4s-wasm.
#   - https://github.com/scala-wasm/scala-wasm/pull/271 (open): the sbt plugin excludes the upstream
#     org.scala-js core libraries. Retires those exclusions in sbt-dapr4s-wasm.
# WHY: those fixes are not in a scala-wasm release yet (newest on Central: 1.22.1-wasm.6). Nothing is
# pushed anywhere: the merge exists only in the local clone.
# RETIRES when a scala-wasm release contains all of them: pin that release in wasm/project/plugins.sbt
# and wasm/build.sbt, and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (the three SHAs + the version) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.scala-wasm keyed by this file's hash.
set -euo pipefail
SCALA_WASM_VERSION="${SCALA_WASM_VERSION:-1.22.1-wasm.7-dapr4s-local}"
BASE=32cda1384fb5a64899103136dd0b09869423c01c # scala-wasm branch, "Merge pull request #268"
PR269=be1184cd1e524476e5efb74d46b4bff14108fe13
PR271=e23da14d9dd32ee55a8f8aef4c6225c7c468a68c
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}"
stampFile="$ivy/io.github.scala-wasm/dapr4s-build-stamp-$SCALA_WASM_VERSION"
stamp="$BASE $PR269 $PR271"
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
for ref in "$BASE" pull/269/head pull/271/head; do git fetch -q origin "$ref"; done
git cat-file -e "$PR269^{commit}" && git cat-file -e "$PR271^{commit}" ||
  { echo "build-scala-wasm.sh: a pinned PR head is gone from GitHub; re-pin it" >&2; exit 1; }
git checkout -q -f --detach "$BASE"
git clean -q -fdx -e target
g() { git -c user.name=dapr4s -c user.email=dapr4s@localhost "$@"; }
g merge -q --no-edit "$PR269"
g merge -q --no-edit "$PR271"
f=ir/shared/src/main/scala/org/scalajs/ir/ScalaJSVersions.scala
sed -i -E "s/current = \"[^\"]+\"/current = \"$SCALA_WASM_VERSION\"/" "$f"
grep -q "current = \"$SCALA_WASM_VERSION\"" "$f" || { echo "build-scala-wasm.sh: cannot set the version" >&2; exit 1; }
# The modules the wasm/ build resolves: the sbt plugin and its 2.12 dependencies (ir, linker-interface,
# linker, test adapter), javalib, and the 2.13 side (library, scalalib, compiler plugin, test bridge).
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
