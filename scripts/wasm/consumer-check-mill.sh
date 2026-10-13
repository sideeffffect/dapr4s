#!/usr/bin/env bash
# Builds the dapr4s test component test/wasm/counter exactly like an external Mill app does: a fresh Mill build
# (out/consumer-check-mill) whose build.mill has ONLY the mill-dapr4s-wasm plugin in its header, one module
# `extends Dapr4sWasmComponentModule` with one dependency (`dapr4s-wasm`), the app's own WIT world + wkg files, and the
# fixed-name ComponentRoot: no in-build workaround. The seven wasm artifacts (incl. the plugin) come from this checkout
# (Mill publishes them, in release mode, into the Maven repository out/consumer-check-mill-repo), everything else from
# Maven Central. Then runs the counter part of scripts/wasm/e2e.sh on the result.
#
# The twin of scripts/wasm/consumer-check.sh (the sbt consumer). The fresh build sits under out/ because Mill never
# looks for build files there.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
mill() { "$repo/mill" --ticker false "$@"; }
local_repo="$repo/out/consumer-check-mill-repo"
rm -rf "$local_repo"
mkdir -p "$local_repo"
modules=('wasm.lib[release].wall' wasm.glue wasm.glueExports wasm.scalalibOverlay wasm.linker wasm.millPlugin)
args=()
for m in "${modules[@]}"; do
  [[ ${#args[@]} -gt 0 ]] && args+=(+)
  args+=("$m.publishM2Local" --m2RepoPath "$local_repo")
done
(cd "$repo" && mill "${args[@]}")
# The version just published (not `show publishVersion`: on a dirty tree it carries a minute timestamp, which a
# second Mill run may already compute differently).
version="$(ls "$local_repo/com/github/sideeffffect/mill-dapr4s-wasm_mill1_3")"
nightly="$(cd "$repo" && mill show scala3Nightly | tail -1 | tr -d '"')"
dir="$repo/out/consumer-check-mill"
rm -rf "$dir"
mkdir -p "$dir/counter/src" "$dir/counter/wit"
cp "$repo/mill" "$dir/"
cp "$repo/wasm/wit/world.wit" "$dir/counter/wit/"
cp "$repo/wasm/wkg-config.toml" "$repo/wasm/wkg.lock" "$dir/counter/"
cp "$repo"/test/wasm/counter/*.scala "$dir/counter/src/"
# The same Mill (and JVM) as dapr4s's own build: the header lines of build.mill.
grep -E '^//\| mill-(version|jvm-version):' "$repo/build.mill" >"$dir/build.mill"
cat >>"$dir/build.mill" <<MILL
//| repositories: ["file://$local_repo"]
//| mvnDeps: ["com.github.sideeffffect::mill-dapr4s-wasm::$version"]
package build

import mill.*
import mill.api.ModuleRef
import mill.scalalib.*
import coursier.maven.MavenRepository
import dapr4s.millwasm.*

// The Scala 3 nightly's compiler bridge lives in the scala-lang nightly repository.
object nightlies extends JvmWorkerModule {
  override def repositoriesTask =
    Task.Anon(super.repositoriesTask() ++ Seq(MavenRepository("https://repo.scala-lang.org/artifactory/maven-nightlies")))
}

object counter extends Dapr4sWasmComponentModule {
  def scalaVersion = "$nightly"
  override def jvmWorker = ModuleRef(nightlies)
  override def repositories = Seq("file://$local_repo", "https://repo.scala-lang.org/artifactory/maven-nightlies")
  override def scalacOptions = Seq("-language:experimental.captureChecking", "-language:experimental.pureFunctions",
    "-Yexplicit-nulls", "-experimental", "-Wconf:any:error")
  override def mvnDeps = Seq(mvn"com.github.sideeffffect::dapr4s-wasm::\$dapr4sWasmVersion")
}
MILL
# The fresh build's Mill daemon is not needed after the check.
trap '(cd "$dir" && ./mill --ticker false shutdown >/dev/null 2>&1) || true' EXIT
echo "consumer-check-mill: building test/wasm/counter against the dapr4s wasm artifacts $version"
component="$(cd "$dir" && ./mill --ticker false show counter.dapr4sComponent | tail -1 | sed -E 's/^"([^:]*:){3}(.*)"$/\2/')"
mkdir -p "$repo/wasm/target/components"
cp "$component" "$repo/wasm/target/components/counter.wasm"
SKIP_BUILD=1 E2E_ONLY=counter "$here/e2e.sh"
