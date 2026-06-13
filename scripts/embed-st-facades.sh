#!/usr/bin/env bash
# Stage the ScalablyTyped facade classes for EMBEDDING into the published dapr4s_sjs1_3 jar.
#
# WHY: js-deps.scala declares the org.scalablytyped facades as compileOnly deps, so the
# published POM never references the ivy-local-only org.scalablytyped coordinates — but the
# linker still needs the facade .sjsir at consumer link time. This script unpacks the
# class/tasty/sjsir entries of EXACTLY the org.scalablytyped jars the three facade roots
# transitively require into a staging directory, which the publish invocation then packs into
# the jar:
#
#   scripts/embed-st-facades.sh
#   scala-cli --power publish --js . --resource-dirs .scala-build/st-embed
#
# The embedded classes live in the dapr4s-specific `dapr4styped.*` package (see
# scripts/generate-st-facades.sh for the rename rationale), so they cannot collide with a
# consumer's own ScalablyTyped generation, which always emits `typings.*`.
#
# The transitive set is computed by coursier from the root coordinates pinned in js-deps.scala
# (the single source of truth — no digest copy here), NOT by globbing
# ~/.ivy2/local/org.scalablytyped: that directory accumulates stale artifacts from older
# digests/generations, and a glob would sweep them in.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JS_DEPS="${REPO_ROOT}/js-deps.scala"
STAGING="${REPO_ROOT}/.scala-build/st-embed"

command -v cs >/dev/null || { echo "ERROR: coursier ('cs') is required on PATH." >&2; exit 1; }

# --- Root coordinates: read from js-deps.scala ---------------------------------------------------
# `org.scalablytyped::name::ver` (scala-cli cross syntax) -> `org.scalablytyped:name_sjs1_3:ver`
# (raw coursier coordinates; the ST jars are always _sjs1_3).
mapfile -t roots < <(
  grep -oE 'using compileOnly\.dep "org\.scalablytyped::[^"]+"' "${JS_DEPS}" \
    | sed -E 's/.*"org\.scalablytyped::([^:]+)::([^"]+)"/org.scalablytyped:\1_sjs1_3:\2/'
)
if [[ "${#roots[@]}" -ne 3 ]]; then
  echo "ERROR: expected exactly 3 org.scalablytyped compileOnly deps in js-deps.scala, found ${#roots[@]}." >&2
  exit 1
fi
echo "Facade roots (from js-deps.scala):"
printf '  %s\n' "${roots[@]}"

# --- Resolve the exact transitive jar set ---------------------------------------------------------
# cs fetch resolves ivy2Local (where generate-st-facades.sh published) plus Maven Central (for
# scala-library/scalajs-library/scalablytyped-runtime/scalajs-dom). Only the org.scalablytyped
# jars get embedded; the Central-hosted ones stay ordinary POM dependencies (js-deps.scala
# declares the two the facades need at link time: scalablytyped-runtime, scalajs-dom).
mapfile -t st_jars < <(
  cs fetch --repository ivy2Local --repository central "${roots[@]}" \
    | grep -E '/org\.scalablytyped/|/org/scalablytyped/'
)
if [[ "${#st_jars[@]}" -lt 3 ]]; then
  echo "ERROR: coursier resolved only ${#st_jars[@]} org.scalablytyped jars — run scripts/generate-st-facades.sh first." >&2
  exit 1
fi

# --- Unpack the .sjsir entries into the staging dir -----------------------------------------------
# ONLY .sjsir — deliberately NOT .class/.tasty. Two reasons:
#   1. Consumers only ever LINK against these facades (the Scala.js linker resolves the
#      `dapr4styped.*` classes the dapr4s .sjsir reference); they never COMPILE against them,
#      because the facade types are entirely internal to dapr4s (src/js/internal) and never
#      surface in the public API. So .sjsir alone is sufficient downstream — verified by linking
#      a `Dapr().serve { ... }` consumer to Wasm against an sjsir-only jar.
#   2. CRITICAL for publishing: `scala-cli publish --js --resource-dirs <this dir>` feeds the dir
#      to scaladoc, which documents every embedded .tasty. With the full node/express/@dapr typings
#      that produced a 1.1 GB `-javadoc.jar` — over the Sonatype Central per-file upload limit, so
#      the Scala.js publish failed with HTTP 400 "length limit exceeded" (v0.20.0). Dropping
#      .tasty/.class leaves scaladoc nothing to document → a ~3 MB doc jar, and shrinks the main
#      jar from ~29 MB to ~9 MB. (META-INF/MANIFEST.MF is excluded by the include pattern too.)
# The ST jars place all their .sjsir under the renamed `dapr4styped/` package root — verified
# below, so a converter change that starts emitting other roots fails loudly instead of silently
# polluting the dapr4s jar namespace.
rm -rf "${STAGING}"
mkdir -p "${STAGING}"
for jar in "${st_jars[@]}"; do
  unzip -qo "${jar}" '*.sjsir' -d "${STAGING}"
done

unexpected="$(find "${STAGING}" -mindepth 1 -maxdepth 1 ! -name 'dapr4styped' -print)"
if [[ -n "${unexpected}" ]]; then
  echo "ERROR: staged entries outside the dapr4styped/ package root:" >&2
  echo "${unexpected}" >&2
  exit 1
fi

echo "Embedded ${#st_jars[@]} ScalablyTyped jars into ${STAGING}:"
echo "  $(find "${STAGING}" -name '*.sjsir' | wc -l) .sjsir files under dapr4styped/ (.class/.tasty deliberately excluded — see above)"
