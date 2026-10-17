#!/usr/bin/env bash
# Prepare the SOURCES of scala-wasm's linker at the exact 1.22.1-wasm.6 release commit + dapr4s's patch
# (scripts/wasm/patches/scala-wasm-params-via-memory.patch) in $1 (default: out/wasm-linker-src). The
# `wasm.linker` module of wasm/package.mill runs this script, compiles those sources with Scala 2.12 against
# the RELEASED scala-wasm ir / linker-interface, adds the released linker jar's resources (the private
# library .sjsir) and republishes the result, under dapr4s's coordinates, as
# com.github.sideeffffect:dapr4s-wasm-linker_2.12 (with scala-wasm's LICENSE and NOTICE).
#
# WHY: the released linker cannot pass WIT function params through linear memory, which the canonical
# ABI requires once the flattened params exceed MAX_FLAT_PARAMS = 16: the EXPORT side is a `???`
# (`on-topic-event`, `on-timer` of the mandatory callbacks hit it) and the IMPORT side silently emits
# nothing (`jobs.schedule`, `conversation.converse`, `actors.register-reminder/-timer` hit it). The patch
# implements both. See wiki/scala-js/scala-wasm-component-spike.md (blocker 4). Nothing is sent upstream
# (decision): the patch lives here.
#
# Until 2026-10 this script built the linker with scala-wasm's own sbt build; Mill now compiles the
# patched sources directly, so dapr4s's build needs no sbt.
#
# The clone is cached in ~/.cache/dapr4s/scala-wasm-src (WORK= overrides); the output directory is
# rewritten on every run.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
patch="$here/patches/scala-wasm-params-via-memory.patch"
# "1.22.1-wasm.6" (scala-wasm/scala-wasm, merged as #266). No GitHub tag exists for it; the sbt plugin
# 1.22.1-wasm.6 on Maven Central was released from this commit.
rev=e24a46b6f1df14483a56c7c02b1787b68bae29a8
out="${1:-$repo/out/wasm-linker-src}"
work="${WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/scala-wasm-src}"
if [[ ! -d "$work/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/scala-wasm/scala-wasm.git "$work"
fi
if ! git -C "$work" cat-file -e "$rev^{commit}" 2>/dev/null; then
  git -C "$work" fetch -q origin "$rev"
fi
rm -rf "$out"
mkdir -p "$out"
git -C "$work" archive "$rev" linker/shared/src/main linker/jvm/src/main LICENSE NOTICE | tar -x -C "$out"
patch -s -p1 -d "$out" <"$patch"
grep -q lower_flat_values "$out/linker/shared/src/main/scala/org/scalajs/linker/backend/wasmemitter/canonicalabi/InteropEmitter.scala" ||
  { echo "prepare-patched-linker.sh: the patch did not apply" >&2; exit 1; }
echo "prepare-patched-linker.sh: patched scala-wasm linker sources ($rev + $(sha256sum "$patch" | cut -c1-16)) in $out"
