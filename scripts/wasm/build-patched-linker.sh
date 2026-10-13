#!/usr/bin/env bash
# Build scala-wasm's linker at the exact 1.22.1-wasm.6 release commit + the export-params patch
# (scripts/wasm/patches/) and publish it to ~/.ivy2/local under the version wasm/build.sbt's linker
# override uses (`patchedLinkerVersion`). Once per machine; CI caches ~/.ivy2/local/io.github.scala-wasm.
#
# WHY: the released linker cannot lift a WIT export whose flattened params exceed MAX_FLAT_PARAMS = 16
# (`??? // TODO read params from linear memory`), and two mandatory exports of the dapr-wasm-components
# `app` world hit it. See wiki/scala-js/scala-wasm-component-spike.md (blocker 4).
#
# Idempotent: exits early when that version is already in ~/.ivy2/local (FORCE=1 rebuilds).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
version="$(sed -n 's/^val patchedLinkerVersion = "\(.*\)"$/\1/p' "$repo/wasm/build.sbt")"
[[ -n "$version" ]] || { echo "build-patched-linker.sh: no patchedLinkerVersion in wasm/build.sbt" >&2; exit 1; }
# "1.22.1-wasm.6" (scala-wasm/scala-wasm, merged as #266). No GitHub tag exists for it; the sbt plugin
# 1.22.1-wasm.6 on Maven Central was released from this commit.
rev=e24a46b6f1df14483a56c7c02b1787b68bae29a8
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}/io.github.scala-wasm/scalajs-linker_2.12/$version"
if [[ -d "$ivy" && "${FORCE:-0}" != 1 ]]; then
  echo "build-patched-linker.sh: $version already in $ivy (FORCE=1 to rebuild)"
  exit 0
fi
work="${WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/scala-wasm-src}"
if [[ ! -d "$work/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/scala-wasm/scala-wasm.git "$work"
fi
cd "$work"
git fetch -q origin "$rev" 2>/dev/null || true
git checkout -q -f "$rev"
git clean -q -fdx -e target
git apply "$here/patches/scala-wasm-export-params-from-memory.patch"
sed -i "s/current = \"1.22.1-wasm.6\",/current = \"$version\",/" ir/shared/src/main/scala/org/scalajs/ir/ScalaJSVersions.scala
grep -q "current = \"$version\"," ir/shared/src/main/scala/org/scalajs/ir/ScalaJSVersions.scala ||
  { echo "build-patched-linker.sh: could not set the IR version to $version" >&2; exit 1; }
sbt -batch ir2_12/publishLocal linkerInterface2_12/publishLocal linker2_12/publishLocal
echo "build-patched-linker.sh: published io.github.scala-wasm:scalajs-linker_2.12:$version"
