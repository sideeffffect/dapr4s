#!/usr/bin/env bash
# Wrapper for `scala-cli test` on the Scala.js WebAssembly backend.
#
# scala-cli 1.14.0 (latest as of 2026-06) always exits 1 after a Wasm test run, even when all
# tests pass: its cleanup does Files.deleteIfExists on the linked output `/tmp/mainXXXX.mjs`,
# which for Wasm is a non-empty DIRECTORY (__loader.js + main.js + main.wasm), so it throws
# java.nio.file.DirectoryNotEmptyException after the test runner already finished.
# (scala.cli.commands.run.Run$.withLinkedJs, Run.scala:728)
#
# This wrapper tolerates EXACTLY that failure mode and nothing else: it exits 0 only when
# scala-cli itself exited 0 (bug fixed upstream), or when the output shows the known exception
# with every suite reporting "0 failed" and no incomplete runs.
#
# Usage: scripts/wasm-test.sh [scala-cli test args...]
#   SCALA_CLI=/path/to/scala-cli scripts/wasm-test.sh --power --js --js-emit-wasm --js-module-kind es .
set -uo pipefail

SCALA_CLI="${SCALA_CLI:-scala-cli}"
log=$(mktemp)
trap 'rm -f "$log" "$log.raw"' EXIT

"$SCALA_CLI" test "$@" 2>&1 | tee "$log.raw"
code=${PIPESTATUS[0]}
# Strip ANSI colors (scala-cli colorizes even when piped).
sed 's/\x1b\[[0-9;]*m//g' "$log.raw" > "$log"

if [ "$code" -eq 0 ]; then
  exit 0  # future-proof: bug fixed upstream
fi

fail=1
if grep -q "DirectoryNotEmptyException" "$log" \
   && ! grep -q "Incomplete runs" "$log" \
   && grep -q "finished: 0 failed" "$log" \
   && ! grep -E "finished: [0-9]+ failed" "$log" | grep -v "finished: 0 failed" | grep -q .; then
  fail=0
fi

if [ "$fail" -eq 0 ]; then
  echo "NOTE: all tests passed; tolerated known scala-cli wasm cleanup bug (DirectoryNotEmptyException on temp .mjs dir)." >&2
  exit 0
fi
exit "$code"
