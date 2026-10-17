#!/usr/bin/env bash
# Build an UNRELEASED scala-java-time from source and publishLocal it (to ~/.ivy2/local) as version
# $SJT_VERSION: scala-java-time_sjs1_3 and scala-java-time-tzdb_sjs1_3. The wasm/ build (build.sbt,
# `scalaJavaTimeWasm`) uses exactly this version. Nothing else of scala-java-time is built.
#
# WHAT: cquiroz/scala-java-time `main` at $BASE, plus a merge of the head of the open upstream PR:
#   - https://github.com/cquiroz/scala-java-time/pull/618 (open): `TimeZone.getTimeZone` computes the
#     raw offset lazily, so `DateTimeFormatter.ofPattern` no longer links `Instant.now` (the wall clock,
#     absent without a JS host).
# and the tzdb module compiled with a Scala 3 NIGHTLY ($TZDB_SCALA) instead of the build's 3.9.0:
#   - `scala-java-time-tzdb`'s provider has `@EnableReflectiveInstantiation`. Its registration runs in a
#     static initializer, which the linker always keeps. Scala 3 <= 3.10.0 emits it with the JS-backed
#     `Reflect.registerInstantiatableClass(..., js.Array(...))`, which does not link without a JS host.
#     Scala 3 emits the Scala-typed `registerInstantiatableClassV2` since
#     https://github.com/scala/scala3/commit/3bb676972 (on the release-3.10.1 branch, not released yet).
#     Core stays on the build's Scala 3 (3.9.0); only the tzdb module needs the newer compiler.
#   - The nightly would also become the tzdb POM's scala3-library version, and that would evict the
#     scala3-library of every app that depends on it. So the script rewrites that one version in the
#     published POM and ivy.xml back to core's. WHY SAFE: the tzdb module's code needs nothing newer
#     from the library (it compiles against 3.9.0 core and is unchanged); only the codegen differs.
# WHY: neither fix is in a scala-java-time release yet (newest on Central: 2.8.0). Nothing is pushed
# anywhere: the merge exists only in the local clone.
# RETIRES when a scala-java-time release contains #618 and is built with Scala 3 >= 3.10.1: pin that
# release in wasm/build.sbt (`scalaJavaTimeWasm`), and delete this script and its CI steps.
#
# Idempotent: skips the build when the stamp (SHAs + compiler + version) matches (FORCE=1 rebuilds).
# The CI wasm leg caches ~/.ivy2/local/io.github.cquiroz keyed by this file's hash.
# Needs a JDK 17 for scala-java-time's build (JDK 21+ fails on its fatal deprecation warnings):
# $SJT_JAVA_HOME, else $JAVA_HOME_17_X64 (set by actions/setup-java), else /usr/lib/jvm/java-17-openjdk-amd64.
set -euo pipefail
SJT_VERSION="${SJT_VERSION:-2.8.1-dapr4s-local}"
BASE=b78d9912b28df70d7b11c213c7620e519ca5ae37 # main, "Merge pull request #617"
PR618=09ead550b8bb56424720970b934b6b54058a35f0
CORE_SCALA=3.9.0
TZDB_SCALA=3.10.2-RC1-bin-20261017-ae374e9-NIGHTLY
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}"
org="$ivy/io.github.cquiroz"
stampFile="$org/dapr4s-build-stamp-$SJT_VERSION"
stamp="$BASE $PR618 $CORE_SCALA $TZDB_SCALA"
tzdbDir="$org/scala-java-time-tzdb_sjs1_3/$SJT_VERSION"
if [[ -f "$stampFile" && "$(cat "$stampFile")" == "$stamp" && -s "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" &&
  "${FORCE:-0}" != 1 ]]; then
  echo "build-scala-java-time.sh: $SJT_VERSION up to date in $ivy (FORCE=1 to rebuild)"
  exit 0
fi
jdk="${SJT_JAVA_HOME:-${JAVA_HOME_17_X64:-/usr/lib/jvm/java-17-openjdk-amd64}}"
[[ -x "$jdk/bin/java" ]] || { echo "build-scala-java-time.sh: no JDK 17 at $jdk (set SJT_JAVA_HOME)" >&2; exit 1; }
work="${WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s/scala-java-time-src}"
if [[ ! -d "$work/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/cquiroz/scala-java-time.git "$work"
fi
cd "$work"
for ref in "$BASE" pull/618/head; do git fetch -q origin "$ref"; done
git cat-file -e "$PR618^{commit}" ||
  { echo "build-scala-java-time.sh: the pinned PR head is gone from GitHub; re-pin it" >&2; exit 1; }
git checkout -q -f --detach "$BASE"
git clean -q -fdx -e target
g() { git -c user.name=dapr4s -c user.email=dapr4s@localhost "$@"; }
g merge -q --no-edit "$PR618"
# `set` per project, not `++`: `++` is sticky and would override the tzdb module's compiler again.
JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" sbt -batch -no-colors --java-home "$jdk" \
  "set ThisBuild / version := \"$SJT_VERSION\"" \
  'set ThisBuild / resolvers += ("scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies")' \
  "set core.js / scalaVersion := \"$CORE_SCALA\"" \
  "set tzdb.js / scalaVersion := \"$TZDB_SCALA\"" \
  'set ThisBuild / packageDoc / publishArtifact := false' \
  coreJS/publishLocal tzdbJS/publishLocal
for f in "$tzdbDir"/poms/*.pom "$tzdbDir"/ivys/ivy.xml; do
  sed -i "s/$TZDB_SCALA/$CORE_SCALA/g" "$f"
  rm -f "$f".md5 "$f".sha1
done
if grep -rq NIGHTLY "$tzdbDir"/poms "$tzdbDir"/ivys; then
  echo "build-scala-java-time.sh: the tzdb POM still names the nightly" >&2
  exit 1
fi
[[ -s "$tzdbDir/jars/scala-java-time-tzdb_sjs1_3.jar" ]] ||
  { echo "build-scala-java-time.sh: scala-java-time-tzdb was not published" >&2; exit 1; }
echo "$stamp" >"$stampFile"
echo "build-scala-java-time.sh: published scala-java-time $SJT_VERSION ($stamp) to $ivy"
