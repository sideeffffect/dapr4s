#!/usr/bin/env bash
# Build ONLY the tzdb module of scala-java-time with Scala 3.10.1 and publishLocal it (to ~/.ivy2/local) as
# scala-java-time-tzdb_sjs1_3 $TZDB_VERSION. Its POM depends on the RELEASED scala-java-time_sjs1_3 2.9.0 (from
# Maven Central; this script does not publish core). The wasm/ build (build.sbt, `scalaJavaTimeTzdbWasm`) uses
# exactly this version.
#
# WHAT: the head of https://github.com/cquiroz/scala-java-time/pull/623 (draft; $BASE = the v2.9.0 tag + two
# build.sbt commits). The PR compiles only `tzdbJS` on the Scala 3 axis with 3.10.1; core stays on 3.9.0, and the
# tzdb module still declares scala3-library 3.9.0 (the 3.10.1 library is on its compile classpath only), so it does
# not evict the scala3-library of an app that depends on it. This script needs no POM rewrite.
# WHY: `scala-java-time-tzdb`'s provider has `@EnableReflectiveInstantiation`. Its registration runs in a static
#   initializer, which the linker always keeps. Scala 3 <= 3.10.0 emits it with the JS-backed
#   `Reflect.registerInstantiatableClass(..., js.Array(...))`, which does not link without a JS host, so the
#   released tzdb 2.9.0 (built with 3.9.0) does not link into a Wasm component. Scala 3 emits the Scala-typed
#   `registerInstantiatableClassV2` since https://github.com/scala/scala3/commit/3bb676972 (in the 3.10.1 release).
#   Core needs no rebuild: 2.9.0 has https://github.com/cquiroz/scala-java-time/pull/618 (`DateTimeFormatter.ofPattern`
#   no longer links `Instant.now`) and nothing in it registers a reflective class.
# RETIRES when a scala-java-time release's tzdb is built with Scala 3 >= 3.10.1 (#623 merged and released): pin that
# release in wasm/build.sbt (`scalaJavaTimeTzdbWasm`), and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (SHA + compiler + version) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.cquiroz keyed by this file's hash.
# Needs a JDK 17 for scala-java-time's build (JDK 21+ fails on its fatal deprecation warnings):
# $SJT_JAVA_HOME, else $JAVA_HOME_17_X64 (set by actions/setup-java), else /usr/lib/jvm/java-17-openjdk-amd64.
set -euo pipefail
CORE_VERSION=2.9.0
TZDB_VERSION="${TZDB_VERSION:-2.9.0-dapr4s-local}"
BASE=32a3b26e5d2c09bce013d1df71c4913d4f219296 # head of cquiroz/scala-java-time#623 (v2.9.0 + 2 commits)
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
git fetch -q origin pull/623/head "$BASE" ||
  { echo "build-scala-java-time.sh: cannot fetch $BASE (cquiroz/scala-java-time#623); re-pin it" >&2; exit 1; }
git checkout -q -f --detach "$BASE"
git clean -q -fdx -e target
# `++ 3` picks each project's own Scala 3 version: 3.10.1 for tzdbJS (#623), 3.9.0 for core.
# `core.js / version` stays the release's, so the tzdb POM depends on the released core from Central.
JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" sbt -batch -no-colors --java-home "$jdk" \
  "++ 3" \
  "set ThisBuild / version := \"$CORE_VERSION\"" \
  "set tzdb.js / version := \"$TZDB_VERSION\"" \
  'set ThisBuild / packageDoc / publishArtifact := false' \
  tzdbJS/publishLocal
grep -A1 "<artifactId>scala3-library_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom | grep -q "<version>$CORE_SCALA</version>" ||
  { echo "build-scala-java-time.sh: the tzdb POM does not declare scala3-library $CORE_SCALA" >&2; exit 1; }
unzip -p "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" | grep -ac registerInstantiatableClassV2 >/dev/null ||
  { echo "build-scala-java-time.sh: the tzdb IR does not use registerInstantiatableClassV2" >&2; exit 1; }
grep -q "<artifactId>scala-java-time_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom &&
  grep -A1 "<artifactId>scala-java-time_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom | grep -q "<version>$CORE_VERSION</version>" ||
  { echo "build-scala-java-time.sh: the tzdb POM does not depend on scala-java-time $CORE_VERSION" >&2; exit 1; }
[[ -s "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" ]] ||
  { echo "build-scala-java-time.sh: scala-java-time-tzdb was not published" >&2; exit 1; }
echo "$stamp" >"$stampFile"
echo "build-scala-java-time.sh: published scala-java-time-tzdb $TZDB_VERSION ($stamp) to $ivy"
