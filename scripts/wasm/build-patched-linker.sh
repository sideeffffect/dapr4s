#!/usr/bin/env bash
# Build scala-wasm's linker at the exact 1.22.1-wasm.6 release commit + dapr4s's patch
# (scripts/wasm/patches/scala-wasm-params-via-memory.patch) and leave the jar (+ sources jar) in
# wasm/target/scala-wasm-linker/. The `wasmLinker` project of wasm/build.sbt republishes it, under
# dapr4s's coordinates, as com.github.sideeffffect:dapr4s-wasm-linker_2.12 (with scala-wasm's LICENSE
# and NOTICE); the sbt-dapr4s-wasm plugin links every app with it. It runs this script itself.
#
# WHY: the released linker cannot pass WIT function params through linear memory, which the canonical
# ABI requires once the flattened params exceed MAX_FLAT_PARAMS = 16: the EXPORT side is a `???`
# (`on-topic-event`, `on-timer` of the mandatory callbacks hit it) and the IMPORT side silently emits
# nothing (`jobs.schedule`, `conversation.converse`, `actors.register-reminder/-timer` hit it). The
# patch implements both. See wiki/scala-js/scala-wasm-component-spike.md (blocker 4). Nothing is sent
# upstream (decision): the patch lives here.
#
# Only the linker module changes; ir, linker-interface, library, javalib and the compiler plugin stay
# the released 1.22.1-wasm.6 artifacts (the patch touches no shared API), so the republished linker
# depends on the released ir/linker-interface.
#
# Idempotent: skips the build when the stamp (commit + patch hash) matches (FORCE=1 rebuilds).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
patch="$here/patches/scala-wasm-params-via-memory.patch"
# "1.22.1-wasm.6" (scala-wasm/scala-wasm, merged as #266). No GitHub tag exists for it; the sbt plugin
# 1.22.1-wasm.6 on Maven Central was released from this commit.
rev=e24a46b6f1df14483a56c7c02b1787b68bae29a8
out="${OUT:-$repo/wasm/target/scala-wasm-linker}"
stamp="$rev $(sha256sum "$patch" | cut -c1-16)"
if [[ -f "$out/stamp" && "$(cat "$out/stamp")" == "$stamp" && -s "$out/scalajs-linker_2.12.jar" && "${FORCE:-0}" != 1 ]]; then
  echo "build-patched-linker.sh: up to date ($stamp) in $out (FORCE=1 to rebuild)"
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
git apply "$patch"
# Stale jars of earlier builds (other versions) would be picked up below: start clean.
find linker -path '*scala-2.12*' -name 'scalajs-linker_2.12-*.jar' -delete
sbt -batch -no-colors linker2_12/package linker2_12/packageSrc
mkdir -p "$out"
jar="$(find linker -path '*scala-2.12*' -name 'scalajs-linker_2.12-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar')"
src="$(find linker -path '*scala-2.12*' -name 'scalajs-linker_2.12-*-sources.jar')"
[[ -s "$jar" && -s "$src" && "$(wc -l <<<"$jar")" == 1 ]] || { echo "build-patched-linker.sh: built jars not found: $jar $src" >&2; exit 1; }
unzip -p "$src" org/scalajs/linker/backend/wasmemitter/canonicalabi/InteropEmitter.scala | grep -q lower_flat_values ||
  { echo "build-patched-linker.sh: the built sources lack the patch" >&2; exit 1; }
cp "$jar" "$out/scalajs-linker_2.12.jar"
cp "$src" "$out/scalajs-linker_2.12-sources.jar"
cp LICENSE NOTICE "$out/"
echo "$stamp" >"$out/stamp"
echo "build-patched-linker.sh: built the patched scala-wasm linker ($stamp) into $out"
