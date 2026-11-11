#!/usr/bin/env bash
# Build the `tzdbScala3Next` project of scala-java-time (the tzdb compiled with Scala 3.10.1) and publishLocal it (to
# ~/.ivy2/local) as scala-java-time-tzdb-scala3-next_sjs1_3 $TZDB_VERSION. Its POM depends on the RELEASED
# scala-java-time_sjs1_3 2.9.0 (from Maven Central; this script does not publish core) and declares scala3-library
# 3.10.1. The Mill build (wasm/package.mill, `ScalaJavaTimeTzdbWasm`) uses exactly this version.
#
# WHAT: the head of https://github.com/cquiroz/scala-java-time/pull/623 (draft; $BASE). The PR adds a new Scala.js-only
#   project `tzdbScala3Next` (artifact `scala-java-time-tzdb-scala3-next`): the same tzdb as `scala-java-time-tzdb`,
#   compiled with Scala 3.10.1. Core and `scala-java-time-tzdb` stay on Scala 3.9.0. Never use both tzdb artifacts
#   together: they contain the same classes.
# WHY: the tzdb provider has `@EnableReflectiveInstantiation`. Its registration runs in a static initializer, which the
#   linker always keeps. Scala 3 <= 3.10.0 emits it with the JS-backed
#   `Reflect.registerInstantiatableClass(..., js.Array(...))`, which does not link without a JS host, so the released
#   tzdb 2.9.0 (built with 3.9.0) does not link into a Wasm component. Scala 3 emits the Scala-typed
#   `registerInstantiatableClassV2` since https://github.com/scala/scala3/commit/3bb676972 (in the 3.10.1 release).
#   Core needs no rebuild: 2.9.0 has https://github.com/cquiroz/scala-java-time/pull/618 (`DateTimeFormatter.ofPattern`
#   no longer links `Instant.now`) and nothing in it registers a reflective class.
# RETIRES when a scala-java-time release contains `scala-java-time-tzdb-scala3-next` (#623 merged and released): pin
#   that release in wasm/package.mill (`ScalaJavaTimeTzdbWasm`), and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (SHA + artifact + compiler + version) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.cquiroz keyed by this file's hash.
# Needs a JDK 17 for scala-java-time's build (JDK 21+ fails on its fatal deprecation warnings):
# $SJT_JAVA_HOME, else $JAVA_HOME_17_X64 (set by actions/setup-java), else /usr/lib/jvm/java-17-openjdk-amd64.
set -euo pipefail
CORE_VERSION=2.9.0
TZDB_VERSION="${TZDB_VERSION:-2.9.0-dapr4s-local}"
BASE=e9c73200ffd10fad23317de448bf754385295edf # head of cquiroz/scala-java-time#623
TZDB_SCALA=3.10.1
ARTIFACT=scala-java-time-tzdb-scala3-next_sjs1_3
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}"
org="$ivy/io.github.cquiroz"
stampFile="$org/dapr4s-build-stamp-tzdb-scala3-next-$TZDB_VERSION"
stamp="$BASE $ARTIFACT $CORE_VERSION $TZDB_SCALA"
tzdbDir="$org/$ARTIFACT/$TZDB_VERSION"
jar="$tzdbDir/jars/$ARTIFACT.jar"
if [[ -f "$stampFile" && "$(cat "$stampFile")" == "$stamp" && -s "$jar" && "${FORCE:-0}" != 1 ]]; then
  echo "build-scala-java-time.sh: $ARTIFACT $TZDB_VERSION up to date in $ivy (FORCE=1 to rebuild)"
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
# `++ 3` puts core.js on Scala 3 (tzdbScala3Next is skipped next to a Scala 2 core); tzdbScala3Next has its own 3.10.1.
# `ThisBuild / version` stays the release's, so the tzdb POM depends on the released core from Central.
JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" sbt -batch -no-colors --java-home "$jdk" \
  "++ 3" \
  "set ThisBuild / version := \"$CORE_VERSION\"" \
  "set tzdbScala3Next / version := \"$TZDB_VERSION\"" \
  'set ThisBuild / packageDoc / publishArtifact := false' \
  tzdbScala3Next/publishLocal
[[ -s "$jar" ]] || { echo "build-scala-java-time.sh: $ARTIFACT was not published" >&2; exit 1; }
grep -A1 "<artifactId>scala3-library_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom | grep -q "<version>$TZDB_SCALA</version>" ||
  { echo "build-scala-java-time.sh: the tzdb POM does not declare scala3-library $TZDB_SCALA" >&2; exit 1; }
unzip -p "$jar" | grep -ac registerInstantiatableClassV2 >/dev/null ||
  { echo "build-scala-java-time.sh: the tzdb IR does not use registerInstantiatableClassV2" >&2; exit 1; }
grep -A1 "<artifactId>scala-java-time_sjs1_3</artifactId>" "$tzdbDir"/poms/*.pom | grep -q "<version>$CORE_VERSION</version>" ||
  { echo "build-scala-java-time.sh: the tzdb POM does not depend on scala-java-time $CORE_VERSION" >&2; exit 1; }
echo "$stamp" >"$stampFile"
echo "build-scala-java-time.sh: published $ARTIFACT $TZDB_VERSION ($stamp) to $ivy"
