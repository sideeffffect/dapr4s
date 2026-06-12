#!/usr/bin/env bash
# Generate the ScalablyTyped facades for the dapr4s Scala.js layer.
#
# Converts the TypeScript type definitions of the npm packages pinned in package.json
# (@dapr/dapr, @types/express, @types/node) into Scala.js facade jars and publishes them to
# the LOCAL ivy repository (~/.ivy2/local/org.scalablytyped/...). js-deps.scala references the
# resulting coordinates as compile-only deps; nothing is committed and nothing is published
# remotely. Run this once per machine (developer or CI) before the first
# `scala-cli compile --js .`, and again whenever the digests change. These ivy-local jars are
# needed only for BUILDING dapr4s itself — at publish time scripts/embed-st-facades.sh embeds
# their classes into the dapr4s_sjs1_3 jar, so dapr4s consumers never run this script.
#
# THE PINNED CONVERTER TUPLE — the digests below are deterministic in exactly these inputs:
#   * package-lock.json contents (i.e. the pinned npm versions in package.json),
#   * the converter version (CONVERTER_VERSION),
#   * the converter flags (--scala, --scalajs, -s / the enabled standard libs,
#     --outputPackage — yes, the output package name is digest-relevant like every other flag).
# Changing ANY of them changes the digests; after a regeneration, update both js-deps.scala and
# the EXPECTED_* variables here (single source of truth check below fails loudly on drift).
set -euo pipefail

CONVERTER_VERSION="1.0.0-beta45"
SCALA_VERSION="3.3.6"     # ST publishes for 3.x; any Scala 3 build (incl. nightlies) consumes _sjs1_3 jars
SCALAJS_VERSION="1.21.0"
STDLIB="es2022"
# The generated facade classes are EMBEDDED into the published dapr4s_sjs1_3 jar
# (scripts/embed-st-facades.sh), so they must NOT live in ScalablyTyped's default `typings.*`
# package: a downstream project that runs its own ScalablyTyped generation (which always emits
# `typings.*`, including its own `typings.std`/`typings.node`) would collide with our embedded
# classes at link time. A dapr4s-specific root package keeps the embedded tree disjoint from
# anything a consumer can generate. It must be a SINGLE identifier: the converter's
# --outputPackage is parsed as one `Name` (Main.scala: `Name(x)`), so a dotted value like
# "dapr4s.typings" would be backtick-escaped into one bizarre identifier, not a nested package.
# --organization stays at the default org.scalablytyped: the ivy-local coordinates are
# compile-time-only for building dapr4s itself and never reach consumers.
OUTPUT_PACKAGE="dapr4styped"

# The expected coordinates (npmVersion-digest), kept in lockstep with js-deps.scala.
EXPECTED_DAPR="3.18.0-d3e034"
EXPECTED_EXPRESS="4.17.21-8ee06b"
EXPECTED_NODE="22.13.0-e98bda"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IVY_LOCAL="${HOME}/.ivy2/local/org.scalablytyped"
JS_DEPS="${REPO_ROOT}/js-deps.scala"

# --- Guard: js-deps.scala must agree with the digests pinned here -------------------------------
for entry in "dapr__dapr::${EXPECTED_DAPR}" "express::${EXPECTED_EXPRESS}" "node::${EXPECTED_NODE}"; do
  if ! grep -qF "org.scalablytyped::${entry}" "${JS_DEPS}"; then
    echo "ERROR: js-deps.scala does not pin org.scalablytyped::${entry}." >&2
    echo "       The digest variables in $(basename "$0") and js-deps.scala have drifted apart;" >&2
    echo "       update them together (see the digest-update procedure in js-deps.scala)." >&2
    exit 1
  fi
done

# --- Skip when the artifacts are already materialised --------------------------------------------
# All three root jars must exist, not just one: an interrupted converter run can leave ivy2Local
# partially populated, and a single-jar marker would then no-op forever while `compile --js`
# keeps failing on the missing org.scalablytyped deps.
all_present=1
for spec in "dapr__dapr_sjs1_3:${EXPECTED_DAPR}" "express_sjs1_3:${EXPECTED_EXPRESS}" "node_sjs1_3:${EXPECTED_NODE}"; do
  artifact="${spec%%:*}"; version="${spec##*:}"
  [[ -f "${IVY_LOCAL}/${artifact}/${version}/jars/${artifact}.jar" ]] || all_present=0
done
if [[ "${all_present}" -eq 1 ]]; then
  echo "ScalablyTyped facades already present in ${IVY_LOCAL}; nothing to do."
  exit 0
fi

# --- Preconditions -------------------------------------------------------------------------------
command -v cs >/dev/null || { echo "ERROR: coursier ('cs') is required on PATH." >&2; exit 1; }
if [[ ! -d "${REPO_ROOT}/node_modules/typescript" ]]; then
  echo "node_modules/typescript missing — running npm install (the converter needs the typescript package)..."
  (cd "${REPO_ROOT}" && npm install)
fi

# --- Convert -------------------------------------------------------------------------------------
# The converter reads package.json/package-lock.json/node_modules from the working directory and
# publishes every converted package (the three roots plus their type-level dependencies) to
# ivy2Local. @types/express and @types/node are conversion roots because they are top-level
# *dependencies* in package.json (devDependencies are not converted).
#
# It also drops its generated .scala sources into ./out of the working directory; that scratch
# tree MUST NOT survive (scala-cli would compile it as project sources), so it is removed when the
# converter exits — the published ivy2Local jars are the only output that matters.
echo "Running ScalablyTyped converter ${CONVERTER_VERSION} (scala ${SCALA_VERSION}, scalajs ${SCALAJS_VERSION}, stdlib ${STDLIB}, outputPackage ${OUTPUT_PACKAGE})..."
trap 'rm -rf "${REPO_ROOT}/out"' EXIT
(cd "${REPO_ROOT}" && cs launch "org.scalablytyped.converter:cli_3:${CONVERTER_VERSION}" -- \
  --scala "${SCALA_VERSION}" --scalajs "${SCALAJS_VERSION}" -s "${STDLIB}" \
  --outputPackage "${OUTPUT_PACKAGE}")

# --- Verify the expected digests came out --------------------------------------------------------
status=0
for spec in "dapr__dapr_sjs1_3:${EXPECTED_DAPR}" "express_sjs1_3:${EXPECTED_EXPRESS}" "node_sjs1_3:${EXPECTED_NODE}"; do
  artifact="${spec%%:*}"; version="${spec##*:}"
  jar="${IVY_LOCAL}/${artifact}/${version}/jars/${artifact}.jar"
  if [[ -f "${jar}" ]]; then
    echo "OK  org.scalablytyped:${artifact}:${version}"
  else
    echo "ERROR: expected ${jar} after conversion, but it does not exist." >&2
    echo "       Produced versions for ${artifact}:" >&2
    ls "${IVY_LOCAL}/${artifact}/" >&2 || true
    echo "       The digest changed — update js-deps.scala and the EXPECTED_* variables together." >&2
    status=1
  fi
done
exit "${status}"
