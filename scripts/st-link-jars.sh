#!/usr/bin/env bash
# Print `--jar <path>` tokens for the ScalablyTyped facade jars, for adding them to the LINK
# classpath of a Scala.js `scala-cli package`/`run` invocation.
#
# WHY: js-deps.scala declares the facades as `compileOnly.dep` so the ivy-local-only
# org.scalablytyped coordinates never reach the published POM (see js-deps.scala). `compileOnly`
# keeps them on the compile AND test classpaths — so `scala-cli compile --js` and
# `scala-cli test --js` link fine — but NOT on the runtime classpath that `scala-cli package`
# (and `run`) link against. Building the JsTestServer main with `package --test` therefore fails
# at link time with "Referring to non-existent class dapr4styped..." unless the facade .sjsir
# are put back on the classpath. This script resolves exactly the transitively-required
# org.scalablytyped jars (the same set scripts/embed-st-facades.sh embeds into the published jar)
# and emits them as `--jar` flags:
#
#   scala-cli --power package --test --js ... $(scripts/st-link-jars.sh) ...
#
# Adding them alongside the compileOnly deps is safe — the linker de-duplicates by class name
# (verified: no duplicate-class errors), and `--jar` affects only this invocation's classpath,
# never the published POM.
#
# The root coordinates are read from js-deps.scala (single source of truth), NOT globbed from
# ~/.ivy2/local/org.scalablytyped, which accumulates stale artifacts from older digests.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JS_DEPS="${REPO_ROOT}/js-deps.scala"

command -v cs >/dev/null || { echo "ERROR: coursier ('cs') is required on PATH." >&2; exit 1; }

# `org.scalablytyped::name::ver` (scala-cli cross syntax) -> `org.scalablytyped:name_sjs1_3:ver`.
mapfile -t roots < <(
  grep -oE 'using compileOnly\.dep "org\.scalablytyped::[^"]+"' "${JS_DEPS}" \
    | sed -E 's/.*"org\.scalablytyped::([^:]+)::([^"]+)"/org.scalablytyped:\1_sjs1_3:\2/'
)
if [[ "${#roots[@]}" -ne 3 ]]; then
  echo "ERROR: expected exactly 3 org.scalablytyped compileOnly deps in js-deps.scala, found ${#roots[@]}." >&2
  exit 1
fi

mapfile -t st_jars < <(
  cs fetch --repository ivy2Local --repository central "${roots[@]}" \
    | grep -E '/org\.scalablytyped/|/org/scalablytyped/'
)
if [[ "${#st_jars[@]}" -lt 3 ]]; then
  echo "ERROR: coursier resolved only ${#st_jars[@]} org.scalablytyped jars — run scripts/generate-st-facades.sh first." >&2
  exit 1
fi

for jar in "${st_jars[@]}"; do
  printf -- '--jar\n%s\n' "${jar}"
done
