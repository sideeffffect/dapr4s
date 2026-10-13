// =================================================================================================
// dapr4s `wasm` platform — Phase 0 prototype (docs/WASM-COMPONENTS-PROPOSAL.md §6)
//
// A dapr4s app (Scala 3 nightly + dapr4s's flags) built as a WASI component for
// dapr-wasm-components 0.7.0 with the scala-wasm fork of Scala.js (`ModuleKind.WasmComponent`).
//
// WHY a separate (nested) sbt build and not projects in ../build.sbt: the scala-wasm sbt plugin
// (`io.github.scala-wasm:sbt-scalajs`) is a FORK of `org.scala-js:sbt-scalajs` with the same classes
// and keys. One meta-build cannot load both, and switching the root build to the fork would change
// coreJS's compiler/linker/library and so its published artifact and POM. A nested build keeps the
// published build byte-for-byte untouched AND makes the wasm target opt-in: `sbt test` at the repo
// root never sees it. Run it with `cd wasm && sbt ...` (see AGENTS.md "Wasm components").
//
// Nothing here is published to Maven Central (`publish / skip := true` everywhere). The reusable
// parts are `publishLocal`-able so external apps (dapr4s-examples) can build against them; see
// "Publishing (publishLocal only)" below for what, and why the rest cannot be a library.
//
// Projects (sources live in the repo's src/wasm, test/wasm trees, like core's src/{shared,jvm,js}):
//
//   coreWasm           dapr4s's shared code (../src/shared) + the wasm platform traits and the
//                      `DaprComponent` entry API (../src/wasm/core). Nightly + dapr4s flags.
//   Scala 2.13 + scala-wasm plugin, ALL GENERATED (project/WitGlue.scala; nobody writes 2.13 code):
//   witLowered         raw wit-bindgen-scala output (scalaJSWitDirectory, world `app`) in the shadow
//                      package `dapr4swit.lowered`
//   witFacade          same-name facade under `dapr4swit` (aliases + import forwarders), one plain
//                      `<Iface>Imports` trait per imported and `<Iface>Exports` trait per exported interface
//   witRootStub        link-time contract stub `dapr4s.internal.witglue.AppRoot` (Provided, never linked)
//   witExports         the 15 `@WitExport` trampolines -> AppRoot. Only COMPONENTS depend on it (its
//                      exports are linker entry points, which would break plain-JS unit-test linking)
//
//   Scala 3 nightly + dapr4s flags:
//   componentRootStub  link-time contract stub `dapr4s.wasm.ComponentRoot` (Provided, never linked)
//   wallInert          inert callback-export implementations (src/wasm/inert)
//   wallWasm           the `wasm` wall: StateCapability over the import traits, error mapping, the
//                      invoke dispatcher, the real `AppRoot` (src/wasm/internal). Unit tests with fakes
//                      in test/wasm/unit, run on Node (`wallWasm/test`).
//   counterRaw         test component: counter directly over the facade (test/wasm/counter-raw)
//   counter            test component: counter on dapr4s's API (test/wasm/counter)
//
//   sbtDapr4sWasm      the sbt-dapr4s-wasm plugin (sbt-plugin/): the build-level workarounds every app
//                      needs. Also compiled into this build's meta-build (project/plugin-sources.sbt).
//
// THE TWO-COMPILER CONSTRAINT: mainline Scala 3 (every nightly included) has no WIT codegen. WIT
// imports are lowered at the CALL SITE by the compiler backend and `@WitExport` is
// compiler-emitted, so every import call and every export must be compiled by Scala 2.13 +
// scala-wasm's compiler plugin. The generator confines that to machine-written code: Scala 3 code
// calls `state_read.get(...)` (a facade forwarder whose body the 2.13 compiler lowered) and
// implements plain export traits.
// =================================================================================================

// The repository root: this build lives in <repo>/wasm.
val repoDir = file("..").getAbsoluteFile.getCanonicalFile
val wasmDir = file(".").getAbsoluteFile.getCanonicalFile
val witDir = wasmDir / "wit"

// Single source of truth: the root build. A nightly bump there applies here on the next load.
def rootBuildVal(name: String): String = {
  val src = IO.read(repoDir / "build.sbt")
  s"""val $name = "([^"]+)"""".r
    .findFirstMatchIn(src)
    .getOrElse(sys.error(s"../build.sbt defines no string val named $name"))
    .group(1)
}
val scala3Nightly = rootBuildVal("scala3Nightly")
val scalaJavaTime = rootBuildVal("scalaJavaTime")
val munitVersion = rootBuildVal("munit")

// Keep in sync with `compilerFlags` in ../build.sbt.
lazy val compilerFlags = Seq(
  "-language:experimental.captureChecking",
  "-language:experimental.pureFunctions",
  "-Ycc-verbose",
  "-Yexplicit-nulls",
  "-experimental",
  "-Wconf:any:error",
)

// The WIT-aware compiler: Scala 2.13 + scala-wasm's `scalajs-compiler` plugin (the officially
// supported path). scala-wasm's only Scala 3 build (scala3-compiler 3.8.3-RC1-wasm.4) is stale
// against library 1.22.1-wasm.6 / wit-bindgen-scala 0.1.0 and rejects the generated bindings
// (96x "not compatible with the Component Model"); see the wiki article
// wiki/scala-js/scala-wasm-component-spike.md, blocker 2.
val scala2Wit = "2.13.18"

// The scala-wasm sbt plugin this build runs on (project/plugins.sbt), which the published sbt-dapr4s-wasm plugin
// depends on. Read from plugins.sbt so the two cannot drift.
val scalaWasmSbtPluginVersion =
  """"io.github.scala-wasm" % "sbt-scalajs" % "([^"]+)"""".r
    .findFirstMatchIn(IO.read(wasmDir / "project" / "plugins.sbt"))
    .getOrElse(sys.error("project/plugins.sbt adds no io.github.scala-wasm:sbt-scalajs"))
    .group(1)

ThisBuild / scalaVersion := scala3Nightly
ThisBuild / organization := "com.github.sideeffffect"
// Same version scheme as the root build (sbt-ci-release): `<tag>+<n>-<sha>-SNAPSHOT` between tags.
ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
// Same formatting rules as the root build (src/wasm and test/wasm are part of the same tree).
ThisBuild / scalafmtConfig := repoDir / ".scalafmt.conf"

// -------------------------------------------------------------------------------------------------
// Publishing (publishLocal only). The version is sbt-dynver's, like the root build's.
//
// publishLocal-able, i.e. what an app needs on its classpath:
//   dapr4s-wasm-core_sjs1_3            coreWasm (shared API + DaprComponent)
//   dapr4s-wasm_sjs1_3                 wallWasm (the wall + the real AppRoot) -> core, inert, facade
//   dapr4s-wasm-inert_sjs1_3           wallInert
//   dapr4s-wasm-wit-lowered_sjs1_2.13  witLowered (generated, 2.13)
//   dapr4s-wasm-wit-facade_sjs1_2.13   witFacade  (generated, 2.13)
//   dapr4s-wasm-wit-exports_sjs1_2.13  witExports (generated, 2.13; a COMPONENT's dependency only)
//   sbt-dapr4s-wasm (sbt 1, 2.12)      sbtDapr4sWasm (the build logic)
// Never published: the two link-time STUBS (witRootStub, componentRootStub). They are `Provided`
// dependencies, so they appear in the dependants' ivy files with conf `provided` and never reach a
// consumer's classpath — on a linker classpath they would shadow the real `AppRoot` / the app's
// `ComponentRoot` (first IR file per class wins). Also never published: the test components.
//
// What cannot be a library (an app gets it from the sbt plugin instead): the patched linker, the
// stdlib .sjsir overlay (it must come FIRST on the linker classpath, which no dependency can
// guarantee), the dependency exclusions, the WasmComponent link + WIT re-embed. And the fixed-name
// `object dapr4s.wasm.ComponentRoot` is written by the app itself (a reactor has no `main`).
//
// `publish / skip := true` everywhere keeps `publish` (Central) impossible; only publishLocal works.
// -------------------------------------------------------------------------------------------------
lazy val noPublish = Seq(publish / skip := true, publishLocal / skip := true, publishArtifact := false)
lazy val publishLocalOnly = Seq(
  publish / skip := true,
  publishLocal / skip := false,
  Compile / packageDoc / publishArtifact := false,
  homepage := Some(url("https://github.com/sideeffffect/dapr4s")),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
)

// -------------------------------------------------------------------------------------------------
// WIT dependencies come from OCI via wkg, never from git: `wit/world.wit` includes
// `dapr-wasm-components:interfaces/app@0.7.0`, `wkg-config.toml` is the upstream file that maps the
// namespace to ghcr.io, and `wkg wit fetch` writes wit/deps/ (git-ignored) + wkg.lock (committed,
// pins the package digest). Runs before wit-bindgen-scala. (Same command as the plugin's
// `dapr4sWasmWitFetch`, which component projects run before re-embedding the WIT world.)
// -------------------------------------------------------------------------------------------------
lazy val witFetch = taskKey[Unit]("Fetch WIT dependencies with `wkg wit fetch` (OCI)")
ThisBuild / witFetch := dapr4s.sbtwasm.Dapr4sWasm.witFetch(witDir, streams.value.log)

// -------------------------------------------------------------------------------------------------
// The upstream workarounds every dapr4s wasm app needs (patched linker, dependency exclusions, stdlib
// .sjsir overlay, WasmComponent link + re-embedding the real WIT world, `wkg wit fetch`) live in the
// sbt-dapr4s-wasm plugin (sbt-plugin/src/main/scala/dapr4s/sbtwasm/Dapr4sWasmPlugin.scala), compiled
// into this meta-build (project/plugin-sources.sbt) AND published locally by `sbtDapr4sWasm` below,
// so that external apps (dapr4s-examples) build exactly like the test components here.
// -------------------------------------------------------------------------------------------------

// Settings of every project compiled by dapr4s's nightly with dapr4s's flags.
// (The dependency exclusions come from the Dapr4sWasmPlugin, enabled on each of these projects.)
lazy val nightly = Seq(
  scalaVersion := scala3Nightly,
  scalacOptions ++= compilerFlags,
)

lazy val witAware = Seq(scalaVersion := scala2Wit)

// Sources live in the repo tree; a project owns only its target directory.
def sourcesIn(dirs: File*) = Seq(
  Compile / unmanagedSourceDirectories := dirs,
  Test / unmanagedSourceDirectories := Nil,
  Compile / unmanagedResourceDirectories := Nil,
  Test / unmanagedResourceDirectories := Nil,
)

// -------------------------------------------------------------------------------------------------
// dapr4s's shared code, compiled for the wasm platform
// -------------------------------------------------------------------------------------------------
lazy val coreWasm = project
  .in(file("core"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(publishLocalOnly, nightly)
  .settings(sourcesIn(repoDir / "src" / "shared", repoDir / "src" / "wasm" / "core"))
  .settings(
    name := "dapr4s-wasm-core",
    // java.time on the Scala.js-family platforms (part of the shared public API). Only
    // Duration-style arithmetic links without JS; formatting/parsing reaches scala-java-locales'
    // `window` (wiki: "What dapr4s's shared code needs on a no-JS target").
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
  )

// -------------------------------------------------------------------------------------------------
// The 2.13 side of the wall: GENERATED code only, compiled by the WIT-aware compiler. Nobody writes
// 2.13 code. See project/WitGlue.scala for the pipeline: raw bindings -> same-name facade + plain
// import/export traits -> link-time root stub -> @WitExport trampolines.
// -------------------------------------------------------------------------------------------------

// The exported interfaces of world `app`, read from the resolved WIT (wit-bindgen-scala generates only
// their types, not their function signatures).
lazy val witExportedInterfaces = taskKey[List[WitGlue.ExportedIface]]("Exported interfaces of WIT world `app`")
ThisBuild / witExportedInterfaces := {
  witFetch.value
  val cmd = Seq("wasm-tools", "component", "wit", witDir.getPath, "--json")
  WitGlue.exportedInterfaces(scala.sys.process.Process(cmd, wasmDir).!!, "app")
}

// wit-bindgen-scala output in the SHADOW package `dapr4swit.lowered`: only the generated facade
// (witFacade) calls it; Scala 3 code never sees it.
lazy val witLowered = project
  .in(file("wit-lowered"))
  .enablePlugins(ScalaJSPlugin)
  .settings(publishLocalOnly, witAware, sourcesIn())
  .settings(
    name := "dapr4s-wasm-wit-lowered",
    scalaJSWitDirectory := witDir,
    scalaJSWitWorld := Some("app"),
    scalaJSWitPackage := Some(WitGlue.Lowered),
    // The plugin also runs the generator for Test; both must wait for wit/deps/ (sbt runs witFetch
    // once per command).
    Compile / scalaJSGenerateWitBindings := (Compile / scalaJSGenerateWitBindings).dependsOn(witFetch).value,
    Test / scalaJSGenerateWitBindings := (Test / scalaJSGenerateWitBindings).dependsOn(witFetch).value,
  )

// The same-name facade under `dapr4swit` (a dapr4s-specific root package, like `dapr4styped` for the
// ScalablyTyped facades, so a consumer's own wit-bindgen output cannot collide with it), the
// `<Iface>Imports` traits with their live objects, and the `<Iface>Exports` traits.
lazy val witFacade = project
  .in(file("wit-facade"))
  .enablePlugins(ScalaJSPlugin)
  .settings(publishLocalOnly, witAware, sourcesIn())
  .settings(
    name := "dapr4s-wasm-wit-facade",
    Compile / sourceGenerators += Def.task {
      val raw = (witLowered / Compile / managedSources).value
      val rawRoot = (witLowered / Compile / sourceManaged).value / "wit-bindgen"
      val out = (Compile / sourceManaged).value / "wit-facade"
      IO.delete(out)
      WitGlue.facade(raw, rawRoot, out, WitGlue.exportTraits(witExportedInterfaces.value))
    }.taskValue,
  )
  .dependsOn(witLowered)

lazy val witRootStub = project
  .in(file("wit-root-stub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, witAware, sourcesIn())
  .settings(
    Compile / sourceGenerators += Def.task {
      WitGlue.rootStub(witExportedInterfaces.value, (Compile / sourceManaged).value / "wit-glue")
    }.taskValue,
  )
  .dependsOn(witFacade)

lazy val witExports = project
  .in(file("wit-exports"))
  .enablePlugins(ScalaJSPlugin)
  .settings(publishLocalOnly, witAware, sourcesIn())
  .settings(
    name := "dapr4s-wasm-wit-exports",
    Compile / sourceGenerators += Def.task {
      WitGlue.trampolines(witExportedInterfaces.value, (Compile / sourceManaged).value / "wit-glue")
    }.taskValue,
  )
  .dependsOn(witFacade, witRootStub % Provided)

// -------------------------------------------------------------------------------------------------
// The Scala 3 side of the wall (dapr4s's nightly + flags)
// -------------------------------------------------------------------------------------------------
lazy val componentRootStub = project
  .in(file("component-root-stub"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "component-root-stub"))
  .dependsOn(coreWasm)

// The inert implementations of the callback exports the prototype does not dispatch yet; shared by the
// wall and the raw test component.
lazy val wallInert = project
  .in(file("wall-inert"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(publishLocalOnly, nightly)
  .settings(name := "dapr4s-wasm-inert")
  .settings(sourcesIn(repoDir / "src" / "wasm" / "inert"))
  .dependsOn(witFacade)

lazy val wallWasm = project
  .in(file("wall"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(publishLocalOnly, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "internal"))
  .settings(
    name := "dapr4s-wasm",
    // Unit tests of the wall (test/wasm/unit) against FAKES of the generated import traits. They link as
    // plain Scala.js (this project keeps the default JS linker config) and run on Node: no WIT import is
    // reachable from them, so they need neither wasmtime nor a sidecar nor the patched linker's export fix.
    Test / unmanagedSourceDirectories := Seq(repoDir / "test" / "wasm" / "unit"),
    libraryDependencies += "org.scalameta" %%% "munit" % munitVersion % Test,
  )
  .dependsOn(coreWasm, wallInert, componentRootStub % Provided)

// -------------------------------------------------------------------------------------------------
// Test apps: linked as components and run by ../scripts/wasm/e2e.sh against a real daprd
// -------------------------------------------------------------------------------------------------
lazy val counterRaw = project
  .in(file("counter-raw"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter-raw"))
  .dependsOn(wallInert, witExports)

lazy val counter = project
  .in(file("counter"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter"))
  .dependsOn(wallWasm, witExports)

// -------------------------------------------------------------------------------------------------
// The sbt-dapr4s-wasm plugin, publishLocal only (an app adds it with addSbtPlugin; it brings the
// scala-wasm fork of sbt-scalajs). Same sources as this build's meta-build (project/plugin-sources.sbt).
// -------------------------------------------------------------------------------------------------
lazy val sbtDapr4sWasm = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(publishLocalOnly)
  .settings(
    name := "sbt-dapr4s-wasm",
    // The meta-build's Scala (sbt 1.x plugins are Scala 2.12), not this build's nightly.
    scalaVersion := scala.util.Properties.versionNumberString,
    scalacOptions := Nil,
    addSbtPlugin("io.github.scala-wasm" % "sbt-scalajs" % scalaWasmSbtPluginVersion),
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    coreWasm,
    witLowered,
    witFacade,
    witRootStub,
    witExports,
    componentRootStub,
    wallInert,
    wallWasm,
    counterRaw,
    counter,
    sbtDapr4sWasm,
  )
  .settings(noPublish)
  .settings(name := "dapr4s-wasm-root")
