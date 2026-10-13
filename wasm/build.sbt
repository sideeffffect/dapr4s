import org.scalajs.linker.interface.{ESVersion, ModuleKind}

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
// Nothing here is published: every project has `publish / skip := true`.
//
// Projects (sources live in the repo's src/wasm, test/wasm trees, like core's src/{shared,jvm,js}):
//
//   coreWasm           dapr4s's shared code (../src/shared) + the wasm platform traits and the
//                      `DaprComponent` entry API (../src/wasm/core). Nightly + dapr4s flags.
//   witBindings        GENERATED WIT bindings (wit-bindgen-scala via scalaJSWitDirectory, world
//                      `app`, package `dapr4swit`) + the plain handler traits. Scala 2.13 + plugin.
//   witRootStub        link-time contract stub `dapr4s.internal.witglue.AppRoot` (Provided, never linked)
//   witExports         the 15 `@WitExport` callback functions + the import forwarders. 2.13 + plugin.
//   componentRootStub  link-time contract stub `dapr4s.wasm.ComponentRoot` (Provided, never linked)
//   wallWasm           the Scala 3 `wasm` wall: StateCapability over WIT, error mapping, the invoke
//                      dispatcher, the wall's `AppRoot`. Nightly + dapr4s flags.
//   counterRaw         test app: counter directly over the generated bindings (test/wasm/counter-raw)
//   counter            test app: counter on dapr4s's API (test/wasm/counter)
//   stdlibOverlay      helper: resolves the fork-patched stdlib .sjsir overlay (no sources)
//
// THE TWO-COMPILER CONSTRAINT: mainline Scala 3 (every nightly included) has no WIT codegen. WIT
// imports are lowered at the CALL SITE by the compiler backend and `@WitExport` is
// compiler-emitted, so the bindings and everything that calls an import or declares an export must
// be compiled by Scala 2.13 + scala-wasm's compiler plugin. The nightly-compiled code calls the
// imports through plain forwarders compiled on the 2.13 side (witExports) and constructs /
// pattern-matches the generated record/variant types directly.
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

// Keep in sync with `compilerFlags` in ../build.sbt.
lazy val compilerFlags = Seq(
  "-language:experimental.captureChecking",
  "-language:experimental.pureFunctions",
  "-Ycc-verbose",
  "-Yexplicit-nulls",
  "-experimental",
  "-Wconf:any:error",
)

val scalaWasmOrg = "io.github.scala-wasm"

// The WIT-aware compiler: Scala 2.13 + scala-wasm's `scalajs-compiler` plugin (the officially
// supported path). scala-wasm's only Scala 3 build (scala3-compiler 3.8.3-RC1-wasm.4) is stale
// against library 1.22.1-wasm.6 / wit-bindgen-scala 0.1.0 and rejects the generated bindings
// (96x "not compatible with the Component Model"); see the wiki article
// wiki/scala-js/scala-wasm-component-spike.md, blocker 2.
val scala2Wit = "2.13.18"

// Stdlib overlay source (blocker 1): scala-wasm's Scala 3 fork build, whose library-js has the
// four classes that the nightly's library-js still implements with JS interop.
val scala3WitStdlib = "3.8.3-RC1-wasm.4"

ThisBuild / scalaVersion := scala3Nightly
ThisBuild / organization := "com.github.sideeffffect"
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
// Same formatting rules as the root build (src/wasm and test/wasm are part of the same tree).
ThisBuild / scalafmtConfig := repoDir / ".scalafmt.conf"

lazy val noPublish = Seq(publish / skip := true, publishLocal / skip := true, publishArtifact := false)

// -------------------------------------------------------------------------------------------------
// WIT dependencies come from OCI via wkg, never from git: `wit/world.wit` includes
// `dapr-wasm-components:interfaces/app@0.7.0`, `wkg-config.toml` is the upstream file that maps the
// namespace to ghcr.io, and `wkg wit fetch` writes wit/deps/ (git-ignored) + wkg.lock (committed,
// pins the package digest). Runs before wit-bindgen-scala.
// -------------------------------------------------------------------------------------------------
lazy val witFetch = taskKey[Unit]("Fetch WIT dependencies with `wkg wit fetch` (OCI)")
ThisBuild / witFetch := {
  val log = streams.value.log
  val cmd = Seq("wkg", "wit", "fetch", "--config", (wasmDir / "wkg-config.toml").getPath)
  log.info(s"Running: ${cmd.mkString(" ")}")
  val exit = scala.sys.process.Process(cmd, wasmDir).!(scala.sys.process.ProcessLogger(log.info(_), log.info(_)))
  if (exit != 0) sys.error(s"wkg wit fetch failed ($exit)")
}

// -------------------------------------------------------------------------------------------------
// Blocker-4 workaround: scala-wasm's linker cannot lift an EXPORT whose flattened params exceed the
// canonical ABI's MAX_FLAT_PARAMS = 16 (`??? // TODO read params from linear memory` in
// InteropEmitter.genWitExportDef). Two mandatory exports of world `app` hit it
// (pubsub-callback.on-topic-event, actors-callback.on-timer). We link with a linker built from the
// 1.22.1-wasm.6 release commit + ../scripts/wasm/patches/scala-wasm-export-params-from-memory.patch,
// published locally (~/.ivy2/local) under the version below by ../scripts/wasm/build-patched-linker.sh.
// Only the LINKER is replaced; plugin, library, javalib and compiler plugin stay the released ones.
// -------------------------------------------------------------------------------------------------
val patchedLinkerVersion = "1.22.1-wasm.6-dapr4s.1"
Global / scalaJSLinkerImpl / fullClasspath := {
  val s = streams.value
  val lm = (scalaJSLinkerImpl / dependencyResolution).value
  lm.retrieve(
    scalaWasmOrg % "scalajs-linker_2.12" % patchedLinkerVersion,
    scalaModuleInfo = None,
    s.cacheDirectory / "scalajs-linker" / patchedLinkerVersion,
    s.log,
  ).fold(
    w =>
      sys.error(
        s"Patched scala-wasm linker $patchedLinkerVersion not found. Build it once per machine with " +
          s"scripts/wasm/build-patched-linker.sh (${w.resolveException.getMessage})",
      ),
    files => files.map(Attributed.blank(_)),
  )
}

// -------------------------------------------------------------------------------------------------
// Blocker-5 workaround: the linker synthesizes the core module's `component-type` section from IR
// signatures, where WIT type aliases are already dealiased (`use types.{metadata}` becomes an inline
// list<tuple<string,string>>). The EXPORTED instance types then lack the alias type exports (e.g.
// `metadata`) that the dapr-wasm-components inbound provider imports, and `wac compose` fails
// ("instance is missing expected list export `metadata`"). `componentWasm` rebuilds the component
// from the linked core module with the REAL world: extract the core module, strip the synthesized
// section, `wasm-tools component embed --encoding utf16` (scala-wasm lowers strings as UTF-16; the
// default utf8 garbles every string) and `wasm-tools component new`. (`withWitDirectory` /
// `withWitWorld` exist in WasmFeatures, but the 1.22.1-wasm.6 linker does not read them.)
// -------------------------------------------------------------------------------------------------
lazy val componentWasm = taskKey[File]("Linked app (fastLinkJS) as a WASI component typed by the real WIT world")
lazy val componentWasmFull = taskKey[File]("Like componentWasm, from fullLinkJS (optimized)")

def extractMainCoreModule(component: File, out: File): Unit = {
  val bytes = IO.readBytes(component)
  require(
    bytes.length > 8 && bytes(0) == 0 && bytes(1) == 'a' && bytes(2) == 's' && bytes(3) == 'm',
    s"$component is not a wasm binary",
  )
  var pos = 8
  def leb(): Int = {
    var result = 0
    var shift = 0
    var b = 0
    while ({
      b = bytes(pos) & 0xff
      pos += 1
      result |= (b & 0x7f) << shift
      shift += 7
      (b & 0x80) != 0
    }) ()
    result
  }
  val modules = scala.collection.mutable.ListBuffer.empty[Array[Byte]]
  while (pos < bytes.length) {
    val id = bytes(pos) & 0xff
    pos += 1
    val size = leb()
    if (id == 1) modules += java.util.Arrays.copyOfRange(bytes, pos, pos + size) // core module section
    pos += size
  }
  // `wasm-tools component new` adds tiny `wit-component:shim` / `:fixups` modules for indirect
  // lowering next to the linker's module; the linker's module is by far the largest.
  require(modules.nonEmpty, s"no core module in $component")
  IO.write(out, modules.maxBy(_.length))
}

def wasmTools(log: Logger, args: String*): Unit = {
  val cmd = "wasm-tools" +: args
  log.info(s"Running: ${cmd.mkString(" ")}")
  val exit = scala.sys.process.Process(cmd).!(scala.sys.process.ProcessLogger(log.info(_), log.warn(_)))
  if (exit != 0) sys.error(s"wasm-tools failed ($exit): ${cmd.mkString(" ")}")
}

def buildComponent(log: Logger, linked: File, work: File, world: String, out: File): File = {
  IO.createDirectory(work)
  IO.createDirectory(out.getParentFile)
  val core = work / "core.wasm"
  val stripped = work / "core-stripped.wasm"
  val embedded = work / "core-embedded.wasm"
  extractMainCoreModule(linked, core)
  wasmTools(log, "strip", "--delete", "component-type.*", core.getPath, "-o", stripped.getPath)
  wasmTools(
    log,
    "component",
    "embed",
    "--encoding",
    "utf16",
    witDir.getPath,
    "--world",
    world,
    stripped.getPath,
    "-o",
    embedded.getPath,
  )
  wasmTools(log, "component", "new", embedded.getPath, "-o", out.getPath)
  wasmTools(log, "validate", out.getPath)
  out
}

// Components land in wasm/target/components/<project>.wasm (and <project>-opt.wasm).
def componentWasmSettings(world: String) = Seq(
  componentWasm := {
    (Compile / fastLinkJS).value
    buildComponent(
      streams.value.log,
      (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
      target.value / "component-fast",
      world,
      wasmDir / "target" / "components" / s"${name.value}.wasm",
    )
  },
  componentWasmFull := {
    (Compile / fullLinkJS).value
    buildComponent(
      streams.value.log,
      (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
      target.value / "component-full",
      world,
      wasmDir / "target" / "components" / s"${name.value}-opt.wasm",
    )
  },
)

lazy val componentLinkSettings = Seq(
  Compile / scalaJSLinkerConfig := {
    (Compile / scalaJSLinkerConfig).value
      .withESFeatures(_.withUseWebAssembly(true).withESVersion(ESVersion.ES2022))
      .withModuleKind(ModuleKind.WasmComponent)
  },
)

// -------------------------------------------------------------------------------------------------
// Blocker-1 workaround: the nightly's Scala.js stdlib (org.scala-js:scalajs-scalalib_2.13:<nightly>,
// built from scala3's library-js against Scala.js 1.20.2) still uses JS interop in a few classes
// (BoxesRunTime.equals uses JS `===`, reachable from any program via Object.toString ->
// MurmurHash3 -> HashMap). scala-wasm's Scala 3 fork patched exactly those library-js files
// (scala-wasm/scala3@17a0deeb: Symbol, ArrayBuilder, Buffer, BoxesRunTime). We take the .sjsir of
// those classes from the fork's published scalalib and put them FIRST on the linker classpath: the
// Scala.js IR loader keeps the first IR file per class name. Must be re-checked on every nightly
// bump (a nightly that changes those four files would be silently shadowed).
// -------------------------------------------------------------------------------------------------
val WasmOverlay = config("wasmoverlay").hide
val overlayClassPrefixes = Seq(
  "scala/runtime/BoxesRunTime",
  "scala/Symbol",
  "scala/collection/mutable/ArrayBuilder",
  "scala/collection/mutable/Buffer",
)
lazy val stdlibOverlayDir = taskKey[File]("Directory with the fork-patched stdlib .sjsir overlay")

// Resolved in its own (non-Scala.js) project so that the `nightly` exclusions cannot touch it.
lazy val stdlibOverlay = project
  .in(file("stdlib-overlay"))
  .settings(noPublish)
  .settings(
    ivyConfigurations += WasmOverlay,
    libraryDependencies += (scalaWasmOrg % "scalajs-scalalib_2.13" % scala3WitStdlib % WasmOverlay).intransitive(),
    stdlibOverlayDir := {
      val jars = update.value.select(configurationFilter(WasmOverlay.name))
      val dir = target.value / "wasm-stdlib-overlay"
      IO.delete(dir)
      jars.foreach { jar =>
        IO.unzip(jar, dir, (n: String) => n.endsWith(".sjsir") && overlayClassPrefixes.exists(p => n.startsWith(p)))
      }
      dir
    },
  )

lazy val wasmStdlibOverlay = Seq(
  Compile / fullClasspath := Attributed.blank(
    (stdlibOverlay / stdlibOverlayDir).value,
  ) +: (Compile / fullClasspath).value,
)

// Settings of every project compiled by dapr4s's nightly with dapr4s's flags.
lazy val nightly = Seq(
  scalaVersion := scala3Nightly,
  scalacOptions ++= compilerFlags,
  // A nightly-compiled project that depends on 2.13-compiled projects must NOT see the 2.13 stdlib
  // that the scala-wasm plugin adds to those (different organization, so no eviction against the
  // nightly's): drop it.
  excludeDependencies ++= Seq(
    ExclusionRule(scalaWasmOrg, "scala-library"),
    ExclusionRule(scalaWasmOrg, "scala3-library_3"),
    ExclusionRule(scalaWasmOrg, "scala3-library_sjs1_3"),
    ExclusionRule(scalaWasmOrg, "scalajs-scalalib_2.13"),
    // Cross-built dependencies (scala-java-time) bring MAINLINE Scala.js (scalajs-library ->
    // scalajs-javalib). Different organization than the fork's, so no eviction: both would be on
    // the classpath and the first IR file per class would win. Keep only the fork's.
    ExclusionRule("org.scala-js", "scalajs-library_2.13"),
    ExclusionRule("org.scala-js", "scalajs-javalib"),
  ),
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
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "shared", repoDir / "src" / "wasm" / "core"))
  .settings(
    name := "dapr4s-wasm-core",
    // java.time on the Scala.js-family platforms (part of the shared public API). Only
    // Duration-style arithmetic links without JS; formatting/parsing reaches scala-java-locales'
    // `window` (wiki: "What dapr4s's shared code needs on a no-JS target").
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
  )

// -------------------------------------------------------------------------------------------------
// The 2.13 side of the wall (WIT-aware compiler)
// -------------------------------------------------------------------------------------------------
lazy val witBindings = project
  .in(file("wit-bindings"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, witAware)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "wit" / "bindings"))
  .settings(
    scalaJSWitDirectory := witDir,
    scalaJSWitWorld := Some("app"),
    // dapr4s-specific root package (like `dapr4styped` for the ScalablyTyped facades), so a
    // consumer's own wit-bindgen output cannot collide with it.
    scalaJSWitPackage := Some("dapr4swit"),
    // The plugin also runs the generator for Test; both must wait for wit/deps/ (sbt runs witFetch
    // once per command).
    Compile / scalaJSGenerateWitBindings := (Compile / scalaJSGenerateWitBindings).dependsOn(witFetch).value,
    Test / scalaJSGenerateWitBindings := (Test / scalaJSGenerateWitBindings).dependsOn(witFetch).value,
  )

lazy val witRootStub = project
  .in(file("wit-root-stub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, witAware)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "wit" / "root-stub"))
  .dependsOn(witBindings)

lazy val witExports = project
  .in(file("wit-exports"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, witAware)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "wit" / "exports"))
  .dependsOn(witBindings, witRootStub % Provided)

// -------------------------------------------------------------------------------------------------
// The Scala 3 side of the wall (dapr4s's nightly + flags)
// -------------------------------------------------------------------------------------------------
lazy val componentRootStub = project
  .in(file("component-root-stub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "component-root-stub"))
  .dependsOn(coreWasm)

lazy val wallWasm = project
  .in(file("wall"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "internal"))
  .dependsOn(coreWasm, witExports, componentRootStub % Provided)

// -------------------------------------------------------------------------------------------------
// Test apps: linked as components and run by ../scripts/wasm/e2e.sh against a real daprd
// -------------------------------------------------------------------------------------------------
lazy val counterRaw = project
  .in(file("counter-raw"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, nightly, componentLinkSettings, wasmStdlibOverlay, componentWasmSettings("app"))
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter-raw"))
  .dependsOn(witExports)

lazy val counter = project
  .in(file("counter"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, nightly, componentLinkSettings, wasmStdlibOverlay, componentWasmSettings("app"))
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter"))
  .dependsOn(wallWasm)

lazy val root = project
  .in(file("."))
  .aggregate(coreWasm, witBindings, witRootStub, witExports, componentRootStub, wallWasm, counterRaw, counter)
  .settings(noPublish)
  .settings(name := "dapr4s-wasm-root")
