#!/usr/bin/env bash
# Build ONLY the tzdb module of scala-java-time v2.9.0 with a newer Scala 3 and publishLocal it (to ~/.ivy2/local)
# as scala-java-time-tzdb_sjs1_3 $TZDB_VERSION. Its POM depends on the RELEASED scala-java-time_sjs1_3 2.9.0 (from
# Maven Central; this script does not publish core). The wasm/ build (build.sbt, `scalaJavaTimeTzdbWasm`) uses
# exactly this version.
#
# WHAT: the module `tzdb` of cquiroz/scala-java-time at tag v2.9.0 ($BASE), compiled with Scala $TZDB_SCALA instead
# of the release's 3.9.0, with only the scala3-library version in its POM rewritten back to 3.9.0.
# WHY: `scala-java-time-tzdb`'s provider has `@EnableReflectiveInstantiation`. Its registration runs in a static
#   initializer, which the linker always keeps. Scala 3 <= 3.10.0 emits it with the JS-backed
#   `Reflect.registerInstantiatableClass(..., js.Array(...))`, which does not link without a JS host, so the
#   released tzdb 2.9.0 (built with 3.9.0) does not link into a Wasm component. Scala 3 emits the Scala-typed
#   `registerInstantiatableClassV2` since https://github.com/scala/scala3/commit/3bb676972 (in the
#   3.10.1 release). Core needs no rebuild: 2.9.0 has
#   https://github.com/cquiroz/scala-java-time/pull/618 (`DateTimeFormatter.ofPattern` no longer links `Instant.now`)
#   and nothing in it registers a reflective class.
# WHY the POM rewrite: the newer compiler would become the tzdb POM's scala3-library version, and that would evict
#   the scala3-library of every app that depends on it. WHY SAFE: the tzdb module's code needs nothing newer from the
#   library (it compiles against core 2.9.0 and is unchanged); only the codegen differs.
# RETIRES when a scala-java-time release's tzdb is built with Scala 3 >= 3.10.1: pin that release in wasm/build.sbt
# (`scalaJavaTimeTzdbWasm`), and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (SHA + compiler + version) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.cquiroz keyed by this file's hash.
# Needs a JDK 17 for scala-java-time's build (JDK 21+ fails on its fatal deprecation warnings):
# $SJT_JAVA_HOME, else $JAVA_HOME_17_X64 (set by actions/setup-java), else /usr/lib/jvm/java-17-openjdk-amd64.
set -euo pipefail
CORE_VERSION=2.9.0
TZDB_VERSION="${TZDB_VERSION:-2.9.0-dapr4s-local}"
BASE=3b4ecad690912d1c4b914a08b9c19d1a67a77cf5 # tag v2.9.0
CORE_SCALA=3.9.0
TZDB_SCALA=3.10.1
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}"
org="$ivy/io.github.cquiroz"
stampFile="$org/dapr4s-build-stamp-tzdb-$TZDB_VERSION"
stamp="$BASE $CORE_VERSION $TZDB_SCALA"
tzdbDir="$org/scala-java-time-tzdb_sjs1_3/$TZDB_VERSION"
if [[ -f "$stampFile" && "$(cat "$stampFile")" == "$stamp" && -s "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" &&
  "${FORCE:-0}" != 1 ]]; then
  echo "build-scala-java-time.sh: tzdb $TZDB_VERSION up to date in $ivy (FORCE=1 to rebuild)"
  exit 0
fi
jdk="${SJT_JAVA_HOME:-${JAVA_HOME_17_X64:-/usr/lib/jvm/java-17-openjdk-amd64}}"
[[ -x "$jdk/bin/java" ]] || { echo "build-scala-java-time.sh: no JDK 17 at $jdk (set SJT_JAVA_HOME)" >&2; exit 1; }
work="${WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/scala-java-time-src}"
if [[ ! -d "$work/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/cquiroz/scala-java-time.git "$work"
fi
cd "$work"
git fetch -q origin "$BASE"
git checkout -q -f --detach "$BASE"
git clean -q -fdx -e target
# `set` per project, not `++`: `++` is sticky and would override the tzdb module's compiler again.
# `core.js / version` stays the release's, so the tzdb POM depends on the released core from Central.
JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" sbt -batch -no-colors --java-home "$jdk" \
  "set ThisBuild / version := \"$CORE_VERSION\"" \
  "set tzdb.js / version := \"$TZDB_VERSION\"" \
  "set core.js / scalaVersion := \"$CORE_SCALA\"" \
  "set tzdb.js / scalaVersion := \"$TZDB_SCALA\"" \
  'set ThisBuild / packageDoc / publishArtifact := false' \
  tzdbJS/publishLocal
for f in "$tzdbDir"/poms/*.pom "$tzdbDir"/ivys/ivy.xml; do
  sed -i "s/$TZDB_SCALA/$CORE_SCALA/g" "$f"
  rm -f "$f".md5 "$f".sha1
done
if grep -rq -- "$TZDB_SCALA" "$tzdbDir"/poms "$tzdbDir"/ivys; then
  echo "build-scala-java-time.sh: the tzdb POM still names $TZDB_SCALA" >&2
  exit 1
fi
grep -q "<artifactId>scala-java-time_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom &&
  grep -A1 "<artifactId>scala-java-time_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom | grep -q "<version>$CORE_VERSION</version>" ||
  { echo "build-scala-java-time.sh: the tzdb POM does not depend on scala-java-time $CORE_VERSION" >&2; exit 1; }
[[ -s "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" ]] ||
  { echo "build-scala-java-time.sh: scala-java-time-tzdb was not published" >&2; exit 1; }
echo "$stamp" >"$stampFile"
echo "build-scala-java-time.sh: published scala-java-time-tzdb $TZDB_VERSION ($stamp) to $ivy"
