#!/usr/bin/env bash
# Builds the dapr4s test component test/wasm/counter exactly like an external sbt app does: a fresh sbt build
# (wasm/target/consumer-check) with ONLY `addSbtPlugin(sbt-dapr4s-wasm)`, `enablePlugins(Dapr4sWasmComponentPlugin)`,
# one libraryDependency (`dapr4s-wasm`), the app's own WIT world + wkg files, and the fixed-name ComponentRoot — no
# in-build workaround. The six wasm artifacts come from this checkout (Mill publishes them, in release mode, into the
# Maven repository wasm/target/consumer-check-repo), everything else from Maven Central. Then runs the counter part
# of scripts/wasm/e2e.sh on the result.
#
# dapr4s's own build is Mill; sbt is needed here because sbt-dapr4s-wasm is a product for sbt consumers
# (dapr4s-examples' wasm/ build uses it).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
mill() { "$repo/mill" --ticker false "$@"; }
local_repo="$repo/wasm/target/consumer-check-repo"
rm -rf "$local_repo"
mkdir -p "$local_repo"
modules=('wasm.lib[release].wall' wasm.glue wasm.glueExports wasm.scalalibOverlay 'wasm.linker[2.12.21]' wasm.sbtPlugin)
args=()
for m in "${modules[@]}"; do
  [[ ${#args[@]} -gt 0 ]] && args+=(+)
  args+=("$m.publishM2Local" --m2RepoPath "$local_repo")
done
(cd "$repo" && mill "${args[@]}")
# The version just published (not `show publishVersion`: on a dirty tree it carries a minute timestamp, which a
# second Mill run may already compute differently).
version="$(ls "$local_repo/com/github/sideeffffect/dapr4s-wasm_sjs1_3")"
nightly="$(cd "$repo" && mill show scala3Nightly | tail -1 | tr -d '"')"
dir="$repo/wasm/target/consumer-check"
rm -rf "$dir"
mkdir -p "$dir/project" "$dir/wit" "$dir/src"
echo "sbt.version=1.13.0" >"$dir/project/build.properties"
cp "$repo/wasm/wit/world.wit" "$dir/wit/"
cp "$repo/wasm/wkg-config.toml" "$repo/wasm/wkg.lock" "$dir/"
cp "$repo"/test/wasm/counter/*.scala "$dir/src/"
cat >"$dir/project/plugins.sbt" <<SBT
resolvers += "dapr4s-local" at "file://$local_repo"
addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % "$version")
SBT
cat >"$dir/build.sbt" <<SBT
ThisBuild / scalaVersion := "$nightly"
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
ThisBuild / resolvers += "dapr4s-local" at "file://$local_repo"
lazy val counter = project
  .in(file("."))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
    scalacOptions ++= Seq("-language:experimental.captureChecking", "-language:experimental.pureFunctions",
      "-Yexplicit-nulls", "-experimental", "-Wconf:any:error"),
    dapr4sWasmComponentsDirectory := file("$repo/wasm/target/components"),
    libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion,
  )
SBT
echo "consumer-check: building test/wasm/counter against the dapr4s wasm artifacts $version"
(cd "$dir" && sbt -batch -no-colors dapr4sComponent)
SKIP_BUILD=1 E2E_ONLY=counter "$here/e2e.sh"
