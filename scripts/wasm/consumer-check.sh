#!/usr/bin/env bash
# Builds the dapr4s test component test/wasm/counter exactly like an external app does: a fresh sbt build
# (wasm/target/consumer-check) with ONLY `addSbtPlugin(sbt-dapr4s-wasm)`, `enablePlugins(Dapr4sWasmComponentPlugin)`,
# one dapr4s libraryDependency (`dapr4s-wasm`), the app's own WIT world + wkg files, and the fixed-name ComponentRoot
# — no in-build workaround. Plus the app's own scala-java-time + scala-java-time-tzdb (`ofPattern`, region zones).
# Everything comes from `cd wasm && sbt publishLocal` (run it first), scripts/wasm/build-scala-java-time.sh and
# Maven Central. Then runs the counter part of scripts/wasm/e2e.sh on the result.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
version="${DAPR4S_WASM_VERSION:-$(cd "$repo/wasm" && sbt -batch -no-colors 'print wallWasm/version' | tail -1 | tr -d '[:space:]')}"
nightly="$(sed -n 's/^val scala3Nightly = "\(.*\)"$/\1/p' "$repo/build.sbt")"
sjt="$(sed -n 's/^val scalaJavaTimeWasm = "\(.*\)"$/\1/p' "$repo/wasm/build.sbt")"
dir="$repo/wasm/target/consumer-check"
rm -rf "$dir"
mkdir -p "$dir/project" "$dir/wit" "$dir/src"
cp "$repo/wasm/project/build.properties" "$dir/project/"
cp "$repo/wasm/wit/world.wit" "$dir/wit/"
cp "$repo/wasm/wkg-config.toml" "$repo/wasm/wkg.lock" "$dir/"
cp "$repo"/test/wasm/counter/*.scala "$dir/src/"
cat >"$dir/project/plugins.sbt" <<SBT
addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % "$version")
SBT
cat >"$dir/build.sbt" <<SBT
ThisBuild / scalaVersion := "$nightly"
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
lazy val counter = project
  .in(file("."))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
    scalacOptions ++= Seq("-language:experimental.captureChecking", "-language:experimental.pureFunctions",
      "-Yexplicit-nulls", "-experimental", "-Wconf:any:error"),
    dapr4sWasmComponentsDirectory := file("$repo/wasm/target/components"),
    libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion,
    // The app's own choice: a scala-java-time with ofPattern on Wasm, and the tz database (as on JS).
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "$sjt",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb" % "$sjt",
  )
SBT
echo "consumer-check: building test/wasm/counter against the published dapr4s wasm artifacts $version"
(cd "$dir" && sbt -batch -no-colors dapr4sComponent)
SKIP_BUILD=1 E2E_ONLY=counter "$here/e2e.sh"
